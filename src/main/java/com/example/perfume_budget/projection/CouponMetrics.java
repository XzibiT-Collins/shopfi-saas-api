package com.example.perfume_budget.projection;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface CouponMetrics {
    Long getId();
    String getCouponCode();
    Long getTotalUsages();
    BigDecimal getTotalDiscountGiven();
    BigDecimal getRevenueGenerated();
    LocalDate getExpirationDate();
}