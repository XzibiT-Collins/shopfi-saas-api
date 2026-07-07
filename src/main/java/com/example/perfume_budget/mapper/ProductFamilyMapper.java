package com.example.perfume_budget.mapper;

import com.example.perfume_budget.dto.product.response.ProductFamilySummaryResponse;
import com.example.perfume_budget.dto.product.response.UnitOfMeasureResponse;
import com.example.perfume_budget.model.ProductFamily;
import com.example.perfume_budget.model.UnitOfMeasure;

public final class ProductFamilyMapper {
    private ProductFamilyMapper() {
        throw new IllegalStateException("Utility class");
    }

    public static ProductFamilySummaryResponse toSummary(ProductFamily family) {
        return new ProductFamilySummaryResponse(
                family.getId(),
                family.getFamilyCode(),
                family.getName(),
                family.getBrand(),
                family.getBaseUnit() != null ? family.getBaseUnit().getId() : null,
                family.getBaseUnit() != null ? family.getBaseUnit().getSku() : null
        );
    }

    public static UnitOfMeasureResponse toResponse(UnitOfMeasure unitOfMeasure) {
        return new UnitOfMeasureResponse(
                unitOfMeasure.getId(),
                unitOfMeasure.getCode(),
                unitOfMeasure.getName()
        );
    }
}
