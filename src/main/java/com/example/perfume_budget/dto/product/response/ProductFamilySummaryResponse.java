package com.example.perfume_budget.dto.product.response;

public record ProductFamilySummaryResponse(
        Long id,
        String familyCode,
        String name,
        String brand,
        Long baseUnitProductId,
        String baseUnitSku
) {
}
