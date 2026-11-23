package com.example.perfume_budget.dto.accounts.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * API-RD-12h: {@code currency} (ISO code, additive) names the unit of every amount. The ledger is single-currency
 * (GHS, like the ledger descriptions), so it is always {@code "GHS"} today.
 */
public record IncomeStatementResponse(
        BigDecimal totalRevenue,
        BigDecimal totalCOGS,
        BigDecimal grossProfit,
        BigDecimal totalExpenses,
        BigDecimal netProfit,
        List<AccountBalanceResponse> revenueAccounts,
        List<AccountBalanceResponse> expenseAccounts,
        String currency
) {
    public static final String LEDGER_CURRENCY = "GHS";

    public IncomeStatementResponse(BigDecimal totalRevenue, BigDecimal totalCOGS, BigDecimal grossProfit, BigDecimal totalExpenses, BigDecimal netProfit, List<AccountBalanceResponse> revenueAccounts, List<AccountBalanceResponse> expenseAccounts) {
        this(totalRevenue, totalCOGS, grossProfit, totalExpenses, netProfit, revenueAccounts, expenseAccounts, LEDGER_CURRENCY);
    }
}

