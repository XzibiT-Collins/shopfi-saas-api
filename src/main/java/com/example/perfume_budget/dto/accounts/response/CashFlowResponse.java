package com.example.perfume_budget.dto.accounts.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * API-RD-12h: {@code currency} (ISO code, additive) names the unit of every amount. The ledger is single-currency
 * (GHS, like the ledger descriptions), so it is always {@code "GHS"} today.
 */
public record CashFlowResponse(
        BigDecimal totalInflows,
        BigDecimal totalOutflows,
        BigDecimal netCashFlow,
        List<DailyCashFlow> dailyCashFlows,
        String currency
) {
    public static final String LEDGER_CURRENCY = "GHS";

    public CashFlowResponse(BigDecimal totalInflows, BigDecimal totalOutflows, BigDecimal netCashFlow, List<DailyCashFlow> dailyCashFlows) {
        this(totalInflows, totalOutflows, netCashFlow, dailyCashFlows, LEDGER_CURRENCY);
    }
}


