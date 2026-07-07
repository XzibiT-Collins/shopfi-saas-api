package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.coupon.request.CouponRequest;
import com.example.perfume_budget.dto.coupon.response.CouponDetailResponse;
import com.example.perfume_budget.dto.coupon.response.CouponListResponse;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.mapper.CouponMapper;
import com.example.perfume_budget.model.Coupon;
import com.example.perfume_budget.repository.CouponRepository;
import com.example.perfume_budget.service.interfaces.CouponService;
import com.example.perfume_budget.utils.CouponCodeGenerator;
import com.example.perfume_budget.utils.PaginationUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CouponServiceImpl implements CouponService {
    private final CouponRepository couponRepository;
    private static final String COUPON_NOT_FOUND = "Coupon not found.";
    private final CouponCodeGenerator couponCodeGenerator;

    @Override
    public CouponDetailResponse createCoupon(CouponRequest request) {
        String couponCode = request.couponCode();
        if(couponCode == null){
            couponCode = couponCodeGenerator.generateCouponCode();
        }
        Coupon newCoupon = CouponMapper.toCoupon(request);
        newCoupon.setCode(couponCode);
        return CouponMapper.toCouponDetailResponse(couponRepository.save(newCoupon));
    }

    @Override
    public CouponDetailResponse getCouponById(Long couponId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new ResourceNotFoundException(COUPON_NOT_FOUND));

        return CouponMapper.toCouponDetailResponse(coupon);
    }

    @Override
    public PageResponse<CouponListResponse> getAllCoupons(Pageable pageable) {
        Page<CouponListResponse> couponList = couponRepository.findAll(pageable).map(CouponMapper::toCouponListResponse);
        return PaginationUtil.createPageResponse(couponList);
    }
}
