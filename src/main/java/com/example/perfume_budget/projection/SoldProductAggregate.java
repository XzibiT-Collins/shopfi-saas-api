package com.example.perfume_budget.projection;

import java.math.BigDecimal;

/** One sold product over a window: quantity + amount sold, plus current global on-hand. */
public interface SoldProductAggregate {
    Long getProductId();
    String getProductName();
    String getSku();
    String getImageUrl();
    Long getQuantitySold();
    BigDecimal getTotalAmount();
    Integer getRemainingStock();
    Integer getLowStockThreshold();
}
