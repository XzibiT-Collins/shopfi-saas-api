package com.example.perfume_budget.dto.accounts.request;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record DateRangeRequest(
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate
) {}