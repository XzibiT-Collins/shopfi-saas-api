package com.example.perfume_budget.dto.coupon.response;

import com.example.perfume_budget.enums.DiscountType;
import lombok.Builder;

import java.time.LocalDate;

@Builder
public record CouponListResponse(
        Long couponId,
        String couponCode,
        Boolean isActive,
        DiscountType discountType,
        Integer usageLimit,
        Integer usageCount,
        LocalDate expirationDate
) {}
