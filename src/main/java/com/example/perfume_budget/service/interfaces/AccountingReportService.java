package com.example.perfume_budget.service.interfaces;

import com.example.perfume_budget.dto.accounts.request.DateRangeRequest;
import com.example.perfume_budget.dto.accounts.response.*;

import java.util.List;

public interface AccountingReportService {
    LedgerSummaryResponse getLedgerSummary();

    IncomeStatementResponse getIncomeStatement(DateRangeRequest dateRange);

    BalanceSheetResponse getBalanceSheet();

    CashFlowResponse getCashFlow(DateRangeRequest dateRange);

    List<JournalEntryResponse> getJournalEntries(DateRangeRequest dateRange);

    List<JournalEntryResponse> getJournalEntriesByReference(
            String referenceType, String referenceId);

    List<JournalEntryResponse> getManualEntries();

    AccountingMetadataResponse getAccountingMetadata();
}
