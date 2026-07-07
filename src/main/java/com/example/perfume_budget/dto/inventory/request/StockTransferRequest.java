package com.example.perfume_budget.dto.inventory.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record StockTransferRequest(
        @NotNull Long productId,
        @NotNull Long fromLocationId,
        @NotNull Long toLocationId,
        @NotNull @Min(1) Integer quantity,
        String note
) {
}
