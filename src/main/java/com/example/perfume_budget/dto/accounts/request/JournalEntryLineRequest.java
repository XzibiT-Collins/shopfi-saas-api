package com.example.perfume_budget.dto.accounts.request;

import com.example.perfume_budget.enums.AccountCategory;
import com.example.perfume_budget.enums.EntryType;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record JournalEntryLineRequest(
        @NotNull AccountCategory accountCategory,
        @NotNull EntryType entryType,
        @NotNull BigDecimal amount,
        String description
) {}