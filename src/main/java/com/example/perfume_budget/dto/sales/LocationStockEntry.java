package com.example.perfume_budget.dto.sales;

/** Current on-hand quantity of a product at one storage location. */
public record LocationStockEntry(
        Long locationId,
        String locationName,
        Integer quantityOnHand
) {
}
