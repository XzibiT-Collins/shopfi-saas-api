package com.example.perfume_budget.events;

import com.example.perfume_budget.enums.OrderProcessingStatus;
import lombok.Builder;

@Builder
public record OrderStatusChangeEvent(
        String orderNumber,
        String customerEmail,
        OrderProcessingStatus status
) {}
