package com.example.perfume_budget.projection;

import java.math.BigDecimal;

public interface TopCompositionMetric {
    Long getProductId();
    String getProductName();
    String getProductImage();
    Long getTotalSold();
    BigDecimal getTotalRevenue();
}