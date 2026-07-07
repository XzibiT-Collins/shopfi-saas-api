package com.example.perfume_budget.mapper;

import com.example.perfume_budget.dto.tax.request.TaxRequest;
import com.example.perfume_budget.dto.tax.response.TaxResponse;
import com.example.perfume_budget.model.Tax;

public class TaxMapper {
    private TaxMapper() {
        throw new IllegalStateException("Utility class");
    }

    public static Tax toTax(TaxRequest request) {
        return Tax.builder()
                .name(request.name())
                .code(request.code())
                .rate(request.rate())
                .isActive(request.isActive() != null ? request.isActive() : true)
                .isCompound(request.isCompound() != null ? request.isCompound() : false)
                .applyOrder(request.applyOrder())
                .build();
    }

    public static TaxResponse toTaxResponse(Tax tax) {
        return new TaxResponse(
                tax.getId(),
                tax.getName(),
                tax.getCode(),
                tax.getRate(),
                tax.getIsActive(),
                tax.getIsCompound(),
                tax.getApplyOrder(),
                tax.getCreatedAt(),
                tax.getUpdatedAt()
        );
    }

    public static void updateTax(Tax tax, TaxRequest request) {
        tax.setName(request.name());
        tax.setCode(request.code());
        tax.setRate(request.rate());
        if (request.isActive() != null) tax.setIsActive(request.isActive());
        if (request.isCompound() != null) tax.setIsCompound(request.isCompound());
        tax.setApplyOrder(request.applyOrder());
    }
}
