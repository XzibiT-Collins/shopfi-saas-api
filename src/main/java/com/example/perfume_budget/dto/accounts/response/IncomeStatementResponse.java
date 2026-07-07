package com.example.perfume_budget.dto.accounts.response;

import java.math.BigDecimal;
import java.util.List;

public record IncomeStatementResponse(
        BigDecimal totalRevenue,
        BigDecimal totalCOGS,
        BigDecimal grossProfit,
        BigDecimal totalExpenses,
        BigDecimal netProfit,
        List<AccountBalanceResponse> revenueAccounts,
        List<AccountBalanceResponse> expenseAccounts
) {}
