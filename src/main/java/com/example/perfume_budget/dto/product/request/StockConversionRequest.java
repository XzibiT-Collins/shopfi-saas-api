package com.example.perfume_budget.dto.product.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record StockConversionRequest(
        @NotNull(message = "Source product ID is required")
        Long sourceProductId,

        @NotNull(message = "Quantity to convert is required")
        @Min(value = 1, message = "Quantity must be at least 1")
        Integer quantity,

        Long targetProductId, // Required only for Reverse Conversion

        String notes
) {}
