package com.example.perfume_budget.mapper;

import com.example.perfume_budget.dto.coupon.request.CouponRequest;
import com.example.perfume_budget.dto.coupon.response.CouponDetailResponse;
import com.example.perfume_budget.dto.coupon.response.CouponListResponse;
import com.example.perfume_budget.model.Coupon;

public class CouponMapper {
    private CouponMapper(){
        throw new IllegalStateException("Utility class");
    }

    public static Coupon toCoupon(CouponRequest request){
        return Coupon.builder()
                .discountType(request.discountType())
                .discountValue(request.discountValue())
                .minimumCartAmountForDiscount(request.minimumCartAmountForDiscount())
                .maximumDiscountAmount(request.maximumDiscountAmount())
                .usageLimit(request.usageLimit())
                .isActive(request.isActive())
                .startDate(request.startDate())
                .usageCount(0)
                .expirationDate(request.expirationDate())
                .build();
    }

    public static CouponDetailResponse toCouponDetailResponse(Coupon coupon){
        return CouponDetailResponse.builder()
                .couponId(coupon.getId())
                .couponCode(coupon.getCode())
                .isActive(coupon.getIsActive())
                .discountType(coupon.getDiscountType())
                .usageLimit(coupon.getUsageLimit())
                .usageCount(coupon.getUsageCount())
                .discountValue(coupon.getDiscountValue())
                .maximumDiscountAmount(coupon.getMaximumDiscountAmount())
                .minimumCartAmountForDiscount(coupon.getMinimumCartAmountForDiscount())
                .startDate(coupon.getStartDate())
                .expirationDate(coupon.getExpirationDate())
                .build();
    }

    public static CouponListResponse toCouponListResponse(Coupon coupon){
        return CouponListResponse.builder()
                .couponId(coupon.getId())
                .couponCode(coupon.getCode())
                .isActive(coupon.getIsActive())
                .discountType(coupon.getDiscountType())
                .usageLimit(coupon.getUsageLimit())
                .usageCount(coupon.getUsageCount())
                .expirationDate(coupon.getExpirationDate())
                .build();
    }
}
