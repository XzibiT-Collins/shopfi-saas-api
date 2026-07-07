package com.example.perfume_budget.dto.tax;

import com.example.perfume_budget.model.Money;
import com.example.perfume_budget.model.OrderTax;
import lombok.Builder;

import java.util.List;

@Builder
public record TaxCalculationResult(
        List<OrderTax> orderTaxes,
        Money totalTaxAmount,
        Money totalAmountAfterTax
) {}
