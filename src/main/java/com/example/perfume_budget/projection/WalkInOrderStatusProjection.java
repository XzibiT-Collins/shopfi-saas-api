package com.example.perfume_budget.projection;

import com.example.perfume_budget.enums.WalkInOrderStatus;

public interface WalkInOrderStatusProjection {
    WalkInOrderStatus getStatus();
    Long getCount();
}
