package com.example.perfume_budget.dto.accounts.response;

import com.example.perfume_budget.enums.EntryType;

import java.math.BigDecimal;

public record JournalEntryLineResponse(
        String accountCode,
        String accountName,
        EntryType entryType,
        BigDecimal amount,
        String description
) {}
