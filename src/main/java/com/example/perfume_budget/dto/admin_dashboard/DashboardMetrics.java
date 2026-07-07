package com.example.perfume_budget.dto.admin_dashboard;

import com.example.perfume_budget.projection.DailyRevenueMetric;
import com.example.perfume_budget.projection.TopCompositionMetric;
import lombok.Builder;

import java.util.List;

@Builder
public record DashboardMetrics(
        String totalRevenue,
        OrderCountMetric orderCountMetric,
        Long totalCustomers,
        Long totalProducts,
        Long totalSiteVisits,
        List<DailyRevenueMetric> dailyRevenueMetric,
        List<TopCompositionMetric> top5Compositions
) {}
