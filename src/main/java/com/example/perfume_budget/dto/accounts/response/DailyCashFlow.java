package com.example.perfume_budget.dto.accounts.response;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailyCashFlow(
        LocalDate date,
        BigDecimal inflow,
        BigDecimal outflow,
        BigDecimal net
) {}
