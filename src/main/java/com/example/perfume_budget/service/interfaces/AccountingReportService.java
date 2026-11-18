package com.example.perfume_budget.service.interfaces;

import com.example.perfume_budget.dto.accounts.request.DateRangeRequest;
import com.example.perfume_budget.dto.accounts.response.*;

import com.example.perfume_budget.dto.PageResponse;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface AccountingReportService {
    LedgerSummaryResponse getLedgerSummary();

    IncomeStatementResponse getIncomeStatement(DateRangeRequest dateRange);

    BalanceSheetResponse getBalanceSheet();

    CashFlowResponse getCashFlow(DateRangeRequest dateRange);

    /** CAT-03: paged (see AccountingController / docs/api/accounting.md). */
    PageResponse<JournalEntryResponse> getJournalEntries(DateRangeRequest dateRange, Pageable pageable);

    List<JournalEntryResponse> getJournalEntriesByReference(
            String referenceType, String referenceId);

    List<JournalEntryResponse> getManualEntries();

    AccountingMetadataResponse getAccountingMetadata();
}
