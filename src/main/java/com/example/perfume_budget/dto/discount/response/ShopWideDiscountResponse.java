package com.example.perfume_budget.dto.discount.response;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Builder
public record ShopWideDiscountResponse(
        Long id,
        String label,
        BigDecimal discountPercentage,
        LocalDateTime startAt,
        LocalDateTime endAt,
        boolean isActive,
        boolean currentlyActive
) {}
