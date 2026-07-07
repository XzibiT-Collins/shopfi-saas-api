package com.example.perfume_budget.dto.accounts.response;

import java.math.BigDecimal;
import java.util.List;

public record CashFlowResponse(
        BigDecimal totalInflows,
        BigDecimal totalOutflows,
        BigDecimal netCashFlow,
        List<DailyCashFlow> dailyCashFlows
) {}

