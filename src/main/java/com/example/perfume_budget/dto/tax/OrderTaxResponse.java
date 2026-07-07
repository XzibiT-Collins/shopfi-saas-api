package com.example.perfume_budget.dto.tax;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record OrderTaxResponse(
        Long id,
        String taxName,
        BigDecimal taxAmount,
        BigDecimal taxRate
) {}
