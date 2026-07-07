package com.example.perfume_budget.dto.analytics;

import java.math.BigDecimal;

public record ProfitDataPointResponse(
        String name,
        BigDecimal grossProfit,
        BigDecimal netProfit
) {}
