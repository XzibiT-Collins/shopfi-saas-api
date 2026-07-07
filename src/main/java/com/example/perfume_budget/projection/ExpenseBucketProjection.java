package com.example.perfume_budget.projection;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface ExpenseBucketProjection {
    LocalDateTime getBucketStart();
    BigDecimal getExpenses();
}
