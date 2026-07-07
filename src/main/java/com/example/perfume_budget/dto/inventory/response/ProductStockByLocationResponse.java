package com.example.perfume_budget.dto.inventory.response;

import java.util.List;

public record ProductStockByLocationResponse(
        Long productId,
        String productName,
        Integer globalStockQuantity,
        Integer outstandingReservedQuantity,
        List<LocationStockLine> locations,
        boolean balancesMatchGlobal
) {
}
