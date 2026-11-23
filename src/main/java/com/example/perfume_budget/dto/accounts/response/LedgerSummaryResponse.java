package com.example.perfume_budget.dto.accounts.response;

import java.math.BigDecimal;

/**
 * API-RD-12h: {@code currency} (ISO code, additive) names the unit of every amount. The ledger is single-currency
 * (GHS, like the ledger descriptions), so it is always {@code "GHS"} today.
 */
public record LedgerSummaryResponse(
        BigDecimal totalRevenue,
        BigDecimal totalExpenses,
        BigDecimal totalAssets,
        BigDecimal totalLiabilities,
        BigDecimal netProfit,
        BigDecimal cashBalance,
        String currency
) {
    public static final String LEDGER_CURRENCY = "GHS";

    public LedgerSummaryResponse(BigDecimal totalRevenue, BigDecimal totalExpenses, BigDecimal totalAssets, BigDecimal totalLiabilities, BigDecimal netProfit, BigDecimal cashBalance) {
        this(totalRevenue, totalExpenses, totalAssets, totalLiabilities, netProfit, cashBalance, LEDGER_CURRENCY);
    }
}

