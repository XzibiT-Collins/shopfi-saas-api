package com.example.perfume_budget.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.dto.accounts.request.DateRangeRequest;
import com.example.perfume_budget.dto.accounts.response.*;
import com.example.perfume_budget.service.interfaces.AccountingReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/admin/accounting")
@RequiredArgsConstructor
public class AccountingController {

    private final AccountingReportService accountingReportService;

    // overall snapshot - no date range needed
    @GetMapping("/summary")
    public ResponseEntity<CustomApiResponse<LedgerSummaryResponse>> getLedgerSummary() {
        return ResponseEntity.ok(CustomApiResponse.success(
                accountingReportService.getLedgerSummary()));
    }

    // income statement for a period
    @GetMapping("/income-statement")
    public ResponseEntity<CustomApiResponse<IncomeStatementResponse>> getIncomeStatement(
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return ResponseEntity.ok(CustomApiResponse.success(
                accountingReportService.getIncomeStatement(
                        new DateRangeRequest(startDate, endDate))));
    }

    // balance sheet - current state of assets, liabilities, equity
    @GetMapping("/balance-sheet")
    public ResponseEntity<CustomApiResponse<BalanceSheetResponse>> getBalanceSheet() {
        return ResponseEntity.ok(CustomApiResponse.success(
                accountingReportService.getBalanceSheet()));
    }

    // cash flow for a period
    @GetMapping("/cash-flow")
    public ResponseEntity<CustomApiResponse<CashFlowResponse>> getCashFlow(
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return ResponseEntity.ok(CustomApiResponse.success(
                accountingReportService.getCashFlow(
                        new DateRangeRequest(startDate, endDate))));
    }

    // full journal entry audit trail
    @GetMapping("/journal-entries")
    public ResponseEntity<CustomApiResponse<List<JournalEntryResponse>>> getJournalEntries(
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return ResponseEntity.ok(CustomApiResponse.success(
                accountingReportService.getJournalEntries(
                        new DateRangeRequest(startDate, endDate))));
    }

    // trace all entries for a specific order or payment
    @GetMapping("/journal-entries/reference")
    public ResponseEntity<CustomApiResponse<List<JournalEntryResponse>>> getEntriesByReference(
            @RequestParam String referenceType,
            @RequestParam String referenceId) {
        return ResponseEntity.ok(CustomApiResponse.success(
                accountingReportService.getJournalEntriesByReference(
                        referenceType, referenceId)));
    }

    // all manual entries for audit review
    @GetMapping("/journal-entries/manual")
    public ResponseEntity<CustomApiResponse<List<JournalEntryResponse>>> getManualEntries() {
        return ResponseEntity.ok(CustomApiResponse.success(
                accountingReportService.getManualEntries()));
    }

    // metadata for select fields
    @GetMapping("/metadata")
    public ResponseEntity<CustomApiResponse<AccountingMetadataResponse>> getMetadata() {
        return ResponseEntity.ok(CustomApiResponse.success(
                accountingReportService.getAccountingMetadata()));
    }
}
