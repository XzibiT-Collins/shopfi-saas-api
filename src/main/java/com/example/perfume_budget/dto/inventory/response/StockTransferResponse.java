package com.example.perfume_budget.dto.inventory.response;

import java.time.LocalDateTime;

public record StockTransferResponse(
        Long id,
        Long productId,
        String productName,
        Long fromLocationId,
        String fromLocationName,
        Long toLocationId,
        String toLocationName,
        Integer quantity,
        String transferType,
        String movedByName,
        String note,
        LocalDateTime createdAt
) {
}
