package com.example.perfume_budget.dto.inventory.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/** INV-01b L3: opt-in repair of exactly these products (never "all"). */
public record LegacyBalanceRepairRequest(
        @NotEmpty(message = "productIds is required")
        @Size(max = 100, message = "At most 100 products per repair")
        List<@NotNull(message = "productIds must not contain null") @Positive(message = "productIds must be positive") Long> productIds
) {
}
