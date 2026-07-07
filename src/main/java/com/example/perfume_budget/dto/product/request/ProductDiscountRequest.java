package com.example.perfume_budget.dto.product.request;

import com.example.perfume_budget.enums.DiscountType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Admin payload to put a single product on sale.
 * Cross-field rules (percentage <= 100, flat < price, endAt after startAt) are enforced in the service.
 */
public record ProductDiscountRequest(
        @NotNull DiscountType discountType,
        @NotNull @DecimalMin(value = "0.0", inclusive = false, message = "Discount value must be greater than zero") BigDecimal discountValue,
        @NotNull LocalDateTime startAt,
        @NotNull LocalDateTime endAt
) {}
