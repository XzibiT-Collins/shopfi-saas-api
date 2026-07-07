package com.example.perfume_budget.dto.coupon.response;

import lombok.Builder;

import java.util.List;

@Builder
public record CouponMetricResponse(
        Integer totalCreated,
        Long totalUsage,
        String totalDiscountGiven,
        String totalRevenueGenerated,
        List<CouponListResponse> coupons
) {}