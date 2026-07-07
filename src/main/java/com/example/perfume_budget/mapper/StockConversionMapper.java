package com.example.perfume_budget.mapper;

import com.example.perfume_budget.dto.product.response.StockConversionResponse;
import com.example.perfume_budget.model.StockConversion;

public final class StockConversionMapper {
    private StockConversionMapper() {
        throw new IllegalStateException("Utility class");
    }

    public static StockConversionResponse toResponse(StockConversion conversion) {
        return new StockConversionResponse(
                conversion.getId(),
                conversion.getConversionNumber(),
                conversion.getDirection(),
                conversion.getFromProduct().getId(),
                conversion.getFromProduct().getName(),
                conversion.getFromQuantity(),
                conversion.getToProduct().getId(),
                conversion.getToProduct().getName(),
                conversion.getToQuantity(),
                conversion.getFromCostValue(),
                conversion.getToCostValue(),
                conversion.getVarianceAmount(),
                conversion.getConvertedBy() != null ? conversion.getConvertedBy().getFullName() : null,
                conversion.getNotes(),
                conversion.getConvertedAt()
        );
    }
}
