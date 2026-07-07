package com.example.perfume_budget.dto.tax.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record TaxRequest(
        @NotBlank(message = "Tax name is required")
        String name,

        @NotBlank(message = "Tax code is required")
        String code,

        @NotNull(message = "Tax rate is required")
        @DecimalMin(value = "0.0", message = "Tax rate cannot be negative")
        BigDecimal rate,

        Boolean isActive,

        Boolean isCompound,

        @NotNull(message = "Apply order is required")
        Integer applyOrder
) {
}
