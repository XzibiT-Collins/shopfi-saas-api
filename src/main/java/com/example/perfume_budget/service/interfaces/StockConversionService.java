package com.example.perfume_budget.service.interfaces;

import com.example.perfume_budget.dto.product.response.ProductVariantSummaryResponse;
import com.example.perfume_budget.dto.product.response.StockConversionResponse;

import java.util.List;

public interface StockConversionService {
    List<ProductVariantSummaryResponse> getReverseConversionTargetVariants(Long sourceProductId);

    /**
     * Converts a bulk product (e.g., BOX) into its base units (e.g., EA).
     * @param sourceProductId The ID of the bulk product being broken down.
     * @param sourceQuantity The number of bulk units to convert.
     * @param notes Optional notes for the conversion.
     * @return The recorded StockConversion.
     */
    StockConversionResponse convertForward(Long sourceProductId, Integer sourceQuantity, String notes);

    /**
     * Repacks base units (e.g., EA) back into a bulk product (e.g., BOX).
     * @param sourceProductId The ID of the base unit product being repacked.
     * @param sourceQuantity The number of base units to repack (must be a multiple of conversion factor).
     * @param targetProductId The ID of the target bulk product.
     * @param notes Optional notes for the conversion.
     * @return The recorded StockConversion.
     */
    StockConversionResponse convertReverse(Long sourceProductId, Integer sourceQuantity, Long targetProductId, String notes);
}
