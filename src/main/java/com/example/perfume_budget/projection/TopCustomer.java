package com.example.perfume_budget.projection;

import java.math.BigDecimal;

public interface TopCustomer {
    String getFullName();
    String getEmail();
    Long getTotalOrders();
    BigDecimal getTotalAmountSpent();
}