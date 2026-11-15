package com.example.perfume_budget.dto.tax;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record OrderTaxResponse(
        Long id,
        String taxName,
        /** Amount in {@code currency} (major units); the currency was implicit before ORD-01. */
        BigDecimal taxAmount,
        /** DEPRECATED: a PERCENT value (15.00 means 15%) with an undocumented unit; use {@code taxRatePercent}. */
        BigDecimal taxRate,
        String taxCode,
        String currency,              // ISO-4217 code of taxAmount, e.g. "GHS"
        String taxAmountFormatted,    // "GHS 12.50", same style as the other money strings
        BigDecimal taxRatePercent     // the rate in percent: 15.00 = 15%
) {}
