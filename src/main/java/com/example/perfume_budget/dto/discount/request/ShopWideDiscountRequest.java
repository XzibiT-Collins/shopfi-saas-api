package com.example.perfume_budget.dto.discount.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Admin payload to set a shop-wide (store-wide) percentage discount for a period.
 * endAt-after-startAt is enforced in the service.
 */
public record ShopWideDiscountRequest(
        @NotBlank(message = "Label is required")
        String label,
        @NotNull
        @DecimalMin(value = "0.0", inclusive = false, message = "Percentage must be greater than zero")
        @DecimalMax(value = "100.0", message = "Percentage cannot exceed 100")
        BigDecimal discountPercentage,
        @NotNull LocalDateTime startAt,
        @NotNull LocalDateTime endAt
) {}
