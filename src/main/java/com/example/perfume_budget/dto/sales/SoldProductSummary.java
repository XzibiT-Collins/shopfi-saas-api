package com.example.perfume_budget.dto.sales;

import java.math.BigDecimal;
import java.util.List;

/** One product's sales roll-up over the window, with current remaining stock (global + per location). */
public record SoldProductSummary(
        Long productId,
        String productName,
        String sku,
        String imageUrl,
        long quantitySold,
        BigDecimal totalAmount,
        Integer remainingStockGlobal,
        Integer lowStockThreshold,
        List<LocationStockEntry> remainingByLocation
) {
}
