package com.example.perfume_budget.dto.analytics;

import java.math.BigDecimal;

public record RevenueDataPointResponse(
        String name,
        BigDecimal revenue
) {}
