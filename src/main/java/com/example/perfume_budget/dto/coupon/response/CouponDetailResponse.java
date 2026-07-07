package com.example.perfume_budget.dto.coupon.response;

import com.example.perfume_budget.enums.DiscountType;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;

@Builder
public record CouponDetailResponse(
        Long couponId,
        String couponCode,
        Boolean isActive,
        DiscountType discountType,
        Integer usageLimit,
        Integer usageCount,
        BigDecimal discountValue,
        BigDecimal maximumDiscountAmount,
        BigDecimal minimumCartAmountForDiscount,
        LocalDate startDate,
        LocalDate expirationDate
) {}