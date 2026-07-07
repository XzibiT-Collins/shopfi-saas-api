package com.example.perfume_budget.dto.tax.response;

import com.example.perfume_budget.dto.tax.OrderTaxResponse;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

@Builder
public record TaxAndDiscountCalculationResponse(
        List<OrderTaxResponse> orderTaxes,
        String totalTaxAmount,
        String totalAmountAfterTax,
        BigDecimal discount
) {
}
