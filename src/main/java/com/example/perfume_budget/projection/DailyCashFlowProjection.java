package com.example.perfume_budget.projection;

import com.example.perfume_budget.enums.EntryType;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface DailyCashFlowProjection {
    LocalDate getDate();
    EntryType getEntryType();
    BigDecimal getTotal();
}
