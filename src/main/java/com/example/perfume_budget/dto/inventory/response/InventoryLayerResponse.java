package com.example.perfume_budget.dto.inventory.response;

import java.time.LocalDateTime;

public record InventoryLayerResponse(
        Long layerId,
        Integer receivedQuantity,
        Integer remainingQuantity,
        String unitCost,
        String unitSellingPrice,
        String sourceType,
        String sourceReference,
        LocalDateTime receivedAt
) {
}
