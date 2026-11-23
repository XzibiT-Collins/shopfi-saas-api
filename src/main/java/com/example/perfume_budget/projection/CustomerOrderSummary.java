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
    /** API-RD-10: newest order of the customer (any status, like the count); null when there is none. */
    default LocalDateTime getLastOrderAt() { return null; }
}
