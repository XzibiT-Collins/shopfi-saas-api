package com.example.perfume_budget.dto.accounts.response;

import java.math.BigDecimal;
import java.util.List;

public record BalanceSheetResponse(
        BigDecimal totalAssets,
        BigDecimal totalLiabilities,
        BigDecimal totalEquity,
        List<AccountBalanceResponse> assetAccounts,
        List<AccountBalanceResponse> liabilityAccounts,
        List<AccountBalanceResponse> equityAccounts
) {}
