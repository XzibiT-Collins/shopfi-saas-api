package com.example.perfume_budget.dto.analytics;

import java.util.List;

public record SalesAnalyticsResponse(
        List<RevenueDataPointResponse> revenueBreakdown,
        List<ProfitDataPointResponse> profitBreakdown,
        List<MiniStatResponse> miniStats,
        List<OrderStatusSliceResponse> orderStatus
) {}
