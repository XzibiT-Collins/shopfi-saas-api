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
}
