package com.example.perfume_budget.dto.walk_in.response;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record WalkInQuoteTaxResponse(
        String taxName,
        String taxCode,
        BigDecimal taxRate,
        String taxableAmount,
        String taxAmount
) {
}
