package com.example.perfume_budget.dto.inventory.response;

import java.time.LocalDateTime;

public record StorageLocationResponse(
        Long id,
        String name,
        String type,
        boolean active,
        Integer lowStockThreshold,
        boolean isDefaultReceiving,
        boolean isWalkInSaleSource,
        boolean isEcommerceFulfilmentSource,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
