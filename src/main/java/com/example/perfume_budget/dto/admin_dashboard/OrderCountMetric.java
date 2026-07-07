package com.example.perfume_budget.dto.admin_dashboard;

import lombok.Builder;

@Builder
public record OrderCountMetric (
        Long totalOrders,
        Long totalDeliveredOrders,
        Long totalPendingOrders,
        Long totalCancelledOrders
){
}
