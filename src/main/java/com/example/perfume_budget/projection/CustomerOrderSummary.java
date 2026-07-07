package com.example.perfume_budget.projection;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface CustomerOrderSummary {
    Long getUserId();
    String getFullName();
    String getEmail();
    boolean getIsActive();
    LocalDateTime getCreatedAt();
    Long getTotalOrders();
    BigDecimal getTotalAmountSpent();
}
