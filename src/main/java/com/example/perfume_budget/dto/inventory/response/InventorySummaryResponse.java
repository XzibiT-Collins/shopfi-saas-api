package com.example.perfume_budget.dto.inventory.response;

import java.util.List;

public record InventorySummaryResponse(
        Long productId,
        String productName,
        Integer stockQuantity,
        String activeCostPrice,
        String activeSellingPrice,
        List<InventoryLayerResponse> layers
) {
}
