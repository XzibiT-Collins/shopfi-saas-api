package com.example.perfume_budget.dto.inventory.response;

import java.time.LocalDateTime;

public record InventoryMovementResponse(
        Long movementId,
        String movementType,
        Integer quantity,
        String unitCost,
        String unitSellingPrice,
        String referenceType,
        String referenceId,
        String recordedBy,
        String note,
        LocalDateTime createdAt
) {
}
