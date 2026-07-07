package com.example.perfume_budget.projection;

public interface LowStockProduct {
    Long getId();
    String getProductName();
    String getProductImage();
    Integer getStockQuantity();
}
