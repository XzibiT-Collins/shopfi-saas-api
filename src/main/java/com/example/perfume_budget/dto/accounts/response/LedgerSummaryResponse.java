package com.example.perfume_budget.dto.accounts.response;

import java.math.BigDecimal;

public record LedgerSummaryResponse(
        BigDecimal totalRevenue,
        BigDecimal totalExpenses,
        BigDecimal totalAssets,
        BigDecimal totalLiabilities,
        BigDecimal netProfit,
        BigDecimal cashBalance
) {}
