package com.example.perfume_budget.projection;


public interface TopProduct {
    Long getId();
    String getProductName();
    Long getViewCount();
    Long getSoldCount();
    Long getAddToCartCount();
}
