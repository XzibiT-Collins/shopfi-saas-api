package com.example.perfume_budget.dto.accounts.response;

import com.example.perfume_budget.enums.JournalEntryType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * API-RD-12h: {@code currency} (ISO code, additive) names the unit of every amount. The ledger is single-currency
 * (GHS, like the ledger descriptions), so it is always {@code "GHS"} today.
 */
public record JournalEntryResponse(
        String entryNumber,
        String description,
        JournalEntryType type,
        String referenceType,
        String referenceId,
        Boolean isManual,
        String recordedBy,
        LocalDateTime transactionDate,
        List<JournalEntryLineResponse> lines,
        String currency
) {
    public static final String LEDGER_CURRENCY = "GHS";

    public JournalEntryResponse(String entryNumber, String description, JournalEntryType type, String referenceType, String referenceId, Boolean isManual, String recordedBy, LocalDateTime transactionDate, List<JournalEntryLineResponse> lines) {
        this(entryNumber, description, type, referenceType, referenceId, isManual, recordedBy, transactionDate, lines, LEDGER_CURRENCY);
    }
}
