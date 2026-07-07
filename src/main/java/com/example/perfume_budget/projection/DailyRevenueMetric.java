package com.example.perfume_budget.projection;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface DailyRevenueMetric {
    LocalDate getDate();
    BigDecimal getRevenue();
}
