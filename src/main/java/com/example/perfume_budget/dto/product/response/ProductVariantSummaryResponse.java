package com.example.perfume_budget.dto.product.response;

/**
 * Target pack of a reverse conversion. SC-BE-01 added (additive) conversionFactor, stockQuantity and uomCode so the
 * screen can offer the pack sizes without one more call per variant.
 */
public record ProductVariantSummaryResponse(
        Long variantId,
        String variantSku,
        String variantName,
        Integer conversionFactor,
        Integer stockQuantity,
        String uomCode
) {
}
