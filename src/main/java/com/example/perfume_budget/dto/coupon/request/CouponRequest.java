package com.example.perfume_budget.dto.coupon.request;

import com.example.perfume_budget.enums.DiscountType;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CouponRequest(
        String couponCode,
        @NotNull DiscountType discountType,
        @NotNull BigDecimal discountValue,
        @NotNull BigDecimal maximumDiscountAmount,
        @NotNull BigDecimal minimumCartAmountForDiscount,
        @NotNull Integer usageLimit,
        @NotNull Boolean isActive,
        @NotNull LocalDate startDate,
        @NotNull LocalDate expirationDate
){}
