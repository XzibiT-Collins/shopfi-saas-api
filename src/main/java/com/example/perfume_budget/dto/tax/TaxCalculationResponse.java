package com.example.perfume_budget.dto.tax;

import lombok.Builder;

import java.util.List;

@Builder
public record TaxCalculationResponse(
        List<OrderTaxResponse> orderTaxes,
        String totalTaxAmount,
        String totalAmountAfterTax
) {}
