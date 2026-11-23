package com.example.perfume_budget.dto.accounts.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * API-RD-12h: {@code currency} (ISO code, additive) names the unit of every amount. The ledger is single-currency
 * (GHS, like the ledger descriptions), so it is always {@code "GHS"} today.
 */
public record BalanceSheetResponse(
        BigDecimal totalAssets,
        BigDecimal totalLiabilities,
        BigDecimal totalEquity,
        List<AccountBalanceResponse> assetAccounts,
        List<AccountBalanceResponse> liabilityAccounts,
        List<AccountBalanceResponse> equityAccounts,
        String currency
) {
    public static final String LEDGER_CURRENCY = "GHS";

    public BalanceSheetResponse(BigDecimal totalAssets, BigDecimal totalLiabilities, BigDecimal totalEquity, List<AccountBalanceResponse> assetAccounts, List<AccountBalanceResponse> liabilityAccounts, List<AccountBalanceResponse> equityAccounts) {
        this(totalAssets, totalLiabilities, totalEquity, assetAccounts, liabilityAccounts, equityAccounts, LEDGER_CURRENCY);
    }
}

