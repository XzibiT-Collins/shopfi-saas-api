package com.example.perfume_budget.dto.accounts.response;

import java.util.List;

public record AccountingMetadataResponse(
        List<EnumResponse> accountCategories,
        List<EnumResponse> journalEntryTypes
) {}
