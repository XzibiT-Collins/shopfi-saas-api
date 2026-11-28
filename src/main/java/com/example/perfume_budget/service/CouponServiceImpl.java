package com.example.perfume_budget.service;

import com.example.perfume_budget.platform.plan.PlanFeature;
import com.example.perfume_budget.entitlement.EntitlementService;
import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.coupon.request.CouponRequest;
import com.example.perfume_budget.dto.coupon.response.CouponDetailResponse;
import com.example.perfume_budget.dto.coupon.response.CouponListResponse;
import com.example.perfume_budget.enums.DiscountType;
import com.example.perfume_budget.exception.ApiCodeException;
import com.example.perfume_budget.exception.BadRequestException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.mapper.CouponMapper;
import com.example.perfume_budget.model.Coupon;
import com.example.perfume_budget.repository.CouponRepository;
import com.example.perfume_budget.repository.OrderRepository;
import com.example.perfume_budget.service.interfaces.CouponService;
import com.example.perfume_budget.utils.CouponCodeGenerator;
import com.example.perfume_budget.utils.PaginationUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


/**
 * Coupon administration (API-04). Edit / deactivate / delete take the coupon's row lock ({@code FOR NO KEY UPDATE},
 * bounded by a lock_timeout), the same row checkout's atomic reservation UPDATE locks for one statement, so they
 * serialise with redemption: once a deactivate has committed no checkout can reserve the coupon. A checkout that
 * reserved before the deactivate committed completes. No coupon lock is ever held across the Paystack call, so an
 * admin action never waits for a slow payment provider.
 *
 * <p>Redeemed coupons: they can be edited (the usage limit cannot drop below the live redemptions) and deactivated;
 * they cannot be deleted while they have a LIVE redemption ({@code 409 COUPON_REDEEMED}). Redemptions of cancelled,
 * failed or expired orders are released and do not block the delete. Orders keep the discount they were charged.
 */
@Service
@RequiredArgsConstructor
public class CouponServiceImpl implements CouponService {
    private final EntitlementService entitlementService;
    public static final String CODE_EXISTS = "COUPON_CODE_EXISTS";
    public static final String CODE_EXISTS_MESSAGE = "A coupon with this code already exists.";
    public static final String REDEEMED = "COUPON_REDEEMED";

    private final CouponRepository couponRepository;
    private static final String COUPON_NOT_FOUND = "Coupon not found.";
    private final CouponCodeGenerator couponCodeGenerator;
    private final OrderRepository orderRepository;
    private final DbLockTimeout lockTimeout;

    /** The one place a code is normalised (trim + upper-case), matching the checkout lookup. */
    public static String normaliseCode(String code) {
        return CouponRedemptionService.normaliseCode(code);
    }

    @Override
    @Transactional
    public CouponDetailResponse createCoupon(CouponRequest request) {
        if (Boolean.TRUE.equals(request.isActive())) {
            // PLAN-02 (plans.md 15.2): an active coupon enters the counted set; locked until this transaction ends
            entitlementService.requireLimitSlot(PlanFeature.MAX_ACTIVE_COUPONS);
        }
        String couponCode = request.couponCode() == null
                ? couponCodeGenerator.generateCouponCode()
                : normaliseCode(request.couponCode());
        if (couponRepository.existsByCode(couponCode)) {
            throw duplicate();
        }
        Coupon newCoupon = CouponMapper.toCoupon(request);
        newCoupon.setCode(couponCode);
        try {
            return CouponMapper.toCouponDetailResponse(couponRepository.saveAndFlush(newCoupon));
        } catch (DataIntegrityViolationException e) {
            // two creates raced past existsByCode: the unique index decides
            throw duplicate();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public CouponDetailResponse getCouponById(Long couponId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new ResourceNotFoundException(COUPON_NOT_FOUND));

        return CouponMapper.toCouponDetailResponse(coupon);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CouponListResponse> getAllCoupons(Pageable pageable) {
        Page<CouponListResponse> couponList = couponRepository.findAll(pageable).map(CouponMapper::toCouponListResponse);
        return PaginationUtil.createPageResponse(couponList);
    }

    @Override
    @Transactional
    public CouponDetailResponse updateCoupon(Long couponId, CouponRequest request) {
        Coupon coupon = lock(couponId);
        if (Boolean.TRUE.equals(request.isActive()) && !Boolean.TRUE.equals(coupon.getIsActive())
                && !coupon.isLimitDeactivated()) {
            // PLAN-02: switching an admin-deactivated coupon back on enters the counted set (is_active OR
            // limit_deactivated); an exhausted coupon is already counted and is never limit-checked
            entitlementService.requireLimitSlot(PlanFeature.MAX_ACTIVE_COUPONS);
        }
        // case-insensitive: sending back a legacy coupon's own code (whatever its case) is not a change
        if (request.couponCode() != null && !normaliseCode(request.couponCode()).equals(normaliseCode(coupon.getCode()))) {
            throw new BadRequestException("Coupon code cannot be changed. Create a new coupon instead.");
        }
        int used = coupon.getUsageCount() == null ? 0 : coupon.getUsageCount();
        if (request.usageLimit() < used) {
            throw new BadRequestException("Usage limit cannot be lower than the " + used
                    + " redemption(s) already made.");
        }
        coupon.setDiscountType(request.discountType());
        coupon.setDiscountValue(request.discountValue());
        coupon.setMaximumDiscountAmount(request.discountType() == DiscountType.PERCENTAGE
                ? request.maximumDiscountAmount() : null);
        coupon.setMinimumCartAmountForDiscount(request.minimumCartAmountForDiscount());
        coupon.setUsageLimit(request.usageLimit());
        coupon.setPerUserUsageLimit(request.perUserUsageLimit());
        coupon.setIsActive(request.isActive());
        coupon.setLimitDeactivated(false); // the admin decided isActive: a release never overrides that
        coupon.setStartDate(request.startDate());
        coupon.setExpirationDate(request.expirationDate());
        return CouponMapper.toCouponDetailResponse(couponRepository.saveAndFlush(coupon));
    }

    @Override
    @Transactional
    public CouponDetailResponse deactivateCoupon(Long couponId) {
        Coupon coupon = lock(couponId);
        coupon.setIsActive(false);
        coupon.setLimitDeactivated(false); // an admin deactivate is never undone by a released redemption
        return CouponMapper.toCouponDetailResponse(couponRepository.saveAndFlush(coupon));
    }

    @Override
    @Transactional
    public void deleteCoupon(Long couponId) {
        // Lock order: the orders that reference the coupon first, then the coupon (the same order -> coupon order as
        // cancel and the payment webhooks); every wait is bounded (503 COUPON_BUSY).
        lockTimeout.run(CouponRedemptionService.BUSY, CouponRedemptionService.BUSY_MESSAGE,
                () -> orderRepository.lockIdsByCouponId(couponId));
        Coupon coupon = lock(couponId);
        // COUPON_REDEEMED counts LIVE redemptions only: in-flight checkouts (usage_count) and orders that are not
        // cancelled / failed / expired. Orders that gave their redemption back do not block the delete.
        boolean redeemed = (coupon.getUsageCount() != null && coupon.getUsageCount() > 0)
                || orderRepository.countLiveCouponRedemptions(couponId) > 0;
        if (redeemed) {
            throw new ApiCodeException(HttpStatus.CONFLICT, REDEEMED,
                    "This coupon has been redeemed and cannot be deleted. Deactivate it instead.");
        }
        // released (cancelled / failed) orders lose the link to the deleted coupon; they keep their amounts
        orderRepository.detachReleasedOrdersFromCoupon(couponId);
        couponRepository.delete(coupon);
        couponRepository.flush();
    }

    private Coupon lock(Long couponId) {
        return lockTimeout.run(CouponRedemptionService.BUSY, CouponRedemptionService.BUSY_MESSAGE,
                () -> couponRepository.lockById(couponId))
                .orElseThrow(() -> new ResourceNotFoundException(COUPON_NOT_FOUND));
    }

    private static ApiCodeException duplicate() {
        return new ApiCodeException(HttpStatus.CONFLICT, CODE_EXISTS, CODE_EXISTS_MESSAGE);
    }
}
