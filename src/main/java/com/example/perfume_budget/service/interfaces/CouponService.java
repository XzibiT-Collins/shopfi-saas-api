package com.example.perfume_budget.service.interfaces;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.coupon.request.CouponRequest;
import com.example.perfume_budget.dto.coupon.response.CouponDetailResponse;
import com.example.perfume_budget.dto.coupon.response.CouponListResponse;
import org.springframework.data.domain.Pageable;


public interface CouponService {
    CouponDetailResponse createCoupon(CouponRequest request);
    CouponDetailResponse getCouponById(Long couponId);
    PageResponse<CouponListResponse> getAllCoupons(Pageable pageable);

    /** Full replace of the editable fields; the code is immutable. */
    CouponDetailResponse updateCoupon(Long couponId, CouponRequest request);

    /** Revoke: stops redemption immediately (idempotent). */
    CouponDetailResponse deactivateCoupon(Long couponId);

    /** Hard delete, only for a coupon that was never redeemed. */
    void deleteCoupon(Long couponId);
}
