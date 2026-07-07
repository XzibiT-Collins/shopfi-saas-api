package com.example.perfume_budget.dto.accounts.request;

import com.example.perfume_budget.enums.JournalEntryType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ManualJournalEntryRequest(
        @NotBlank String description,
        @NotNull JournalEntryType type,
        @NotNull @NotEmpty List<JournalEntryLineRequest> lines
) {}
