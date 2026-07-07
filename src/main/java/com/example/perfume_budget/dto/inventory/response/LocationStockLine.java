package com.example.perfume_budget.dto.inventory.response;

public record LocationStockLine(
        Long locationId,
        String locationName,
        String locationType,
        Integer quantityOnHand
) {
}
