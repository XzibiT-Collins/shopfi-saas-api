package com.example.perfume_budget.dto.inventory.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record InventoryReceiptRequest(
        @NotNull(message = "Product ID is required")
        Long productId,
        @NotNull(message = "Quantity is required")
        @Min(value = 1, message = "Quantity must be at least 1")
        Integer quantity,
        @NotNull(message = "Unit cost is required")
        BigDecimal unitCost,
        @NotNull(message = "Unit selling price is required")
        BigDecimal unitSellingPrice,
        LocalDateTime receivedAt,
        @NotBlank(message = "Reference is required")
        String reference,
        String note
) {
}
