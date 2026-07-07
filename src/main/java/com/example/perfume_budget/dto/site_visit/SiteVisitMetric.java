package com.example.perfume_budget.dto.site_visit;

import com.example.perfume_budget.projection.PageVisitMetric;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

@Builder
public record SiteVisitMetric(
        Long totalVisits,
        Long totalUniqueVisitors,
        Long totalPageViews,
        BigDecimal uniqueVisitToOrderConversionRate,
        List<PageVisitMetric> top5MostVisitedPages
) {}
