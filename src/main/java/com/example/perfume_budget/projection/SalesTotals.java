package com.example.perfume_budget.projection;

import java.math.BigDecimal;

/** Window-wide sales roll-up (overall, or for a single product). */
public interface SalesTotals {
    Long getTotalQuantity();
    BigDecimal getTotalAmount();
}
