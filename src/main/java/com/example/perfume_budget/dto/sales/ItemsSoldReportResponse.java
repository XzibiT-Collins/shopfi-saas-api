package com.example.perfume_budget.dto.sales;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.enums.SalesAnalyticsSource;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Aggregated items-sold report: products sold in [from, to] plus overall totals. */
public record ItemsSoldReportResponse(
        LocalDate from,
        LocalDate to,
        SalesAnalyticsSource source,
        PageResponse<SoldProductSummary> products,
        long overallQuantitySold,
        BigDecimal overallAmountSold,
        CurrencyCode currency
) {
}
