package com.example.perfume_budget.dto.product.response;

public record ProductVariantSummaryResponse(
        Long variantId,
        String variantSku,
        String variantName
) {
}
