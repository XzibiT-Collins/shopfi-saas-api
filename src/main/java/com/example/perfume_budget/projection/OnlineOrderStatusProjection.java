package com.example.perfume_budget.projection;

import com.example.perfume_budget.enums.OrderProcessingStatus;

public interface OnlineOrderStatusProjection {
    OrderProcessingStatus getDeliveryStatus();
    Long getCount();
}
