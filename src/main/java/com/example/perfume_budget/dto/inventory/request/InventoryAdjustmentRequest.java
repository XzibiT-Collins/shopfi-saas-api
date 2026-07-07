package com.example.perfume_budget.dto.inventory.request;

import com.example.perfume_budget.enums.InventoryAdjustmentDirection;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record InventoryAdjustmentRequest(
        @NotNull(message = "Product ID is required")
        Long productId,
        @NotNull(message = "Adjustment direction is required")
        InventoryAdjustmentDirection direction,
        @NotNull(message = "Quantity is required")
        @Min(value = 1, message = "Quantity must be at least 1")
        Integer quantity,
        @NotBlank(message = "Reason is required")
        String reason,
        BigDecimal unitCost,
        BigDecimal unitSellingPrice,
        String reference,
        String note
) {
}
