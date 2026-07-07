package com.example.perfume_budget.dto.accounts.response;

import com.example.perfume_budget.enums.JournalEntryType;

import java.time.LocalDateTime;
import java.util.List;

public record JournalEntryResponse(
        String entryNumber,
        String description,
        JournalEntryType type,
        String referenceType,
        String referenceId,
        Boolean isManual,
        String recordedBy,
        LocalDateTime transactionDate,
        List<JournalEntryLineResponse> lines
) {}