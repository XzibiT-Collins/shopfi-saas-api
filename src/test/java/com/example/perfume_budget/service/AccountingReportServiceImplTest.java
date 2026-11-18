package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.accounts.request.DateRangeRequest;
import com.example.perfume_budget.dto.accounts.response.*;
import com.example.perfume_budget.enums.AccountCategory;
import com.example.perfume_budget.enums.AccountType;
import com.example.perfume_budget.enums.EntryType;
import com.example.perfume_budget.enums.JournalEntryType;
import com.example.perfume_budget.model.JournalEntry;
import com.example.perfume_budget.model.JournalEntryLine;
import com.example.perfume_budget.model.LedgerAccount;
import com.example.perfume_budget.projection.DailyCashFlowProjection;
import com.example.perfume_budget.repository.JournalEntryRepository;
import com.example.perfume_budget.repository.LedgerAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountingReportServiceImplTest {

    @Mock
    private JournalEntryRepository journalEntryRepository;

    @Mock
    private LedgerAccountRepository ledgerAccountRepository;

    @InjectMocks
    private AccountingReportServiceImpl accountingReportService;

    private LedgerAccount cashAccount;
    private LedgerAccount revenueAccount;
    private LedgerAccount expenseAccount;
    private LedgerAccount liabilityAccount;

    @BeforeEach
    void setUp() {
        cashAccount = LedgerAccount.builder()
                .id(1L)
                .code("1001")
                .name("Cash")
                .type(AccountType.ASSET)
                .category(AccountCategory.CASH)
                .balance(new BigDecimal("1000.00"))
                .isActive(true)
                .build();

        revenueAccount = LedgerAccount.builder()
                .id(2L)
                .code("4001")
                .name("Sales")
                .type(AccountType.REVENUE)
                .category(AccountCategory.SALES_REVENUE)
                .balance(new BigDecimal("5000.00"))
                .isActive(true)
                .build();

        expenseAccount = LedgerAccount.builder()
                .id(3L)
                .code("5001")
                .name("COGS")
                .type(AccountType.EXPENSE)
                .category(AccountCategory.COGS)
                .balance(new BigDecimal("2000.00"))
                .isActive(true)
                .build();

        liabilityAccount = LedgerAccount.builder()
                .id(4L)
                .code("2001")
                .name("Accounts Payable")
                .type(AccountType.LIABILITY)
                .category(AccountCategory.ACCOUNTS_PAYABLE)
                .balance(new BigDecimal("500.00"))
                .isActive(true)
                .build();
    }

    @Test
    @DisplayName("Get Ledger Summary - Success")
    void getLedgerSummary_Success() {
        when(ledgerAccountRepository.findAllByIsActiveTrueOrderByCodeAsc())
                .thenReturn(Arrays.asList(cashAccount, revenueAccount, expenseAccount, liabilityAccount));

        LedgerSummaryResponse result = accountingReportService.getLedgerSummary();

        assertNotNull(result);
        assertEquals(new BigDecimal("5000.00"), result.totalRevenue());
        assertEquals(new BigDecimal("2000.00"), result.totalExpenses());
        assertEquals(new BigDecimal("1000.00"), result.totalAssets());
        assertEquals(new BigDecimal("500.00"), result.totalLiabilities());
        assertEquals(new BigDecimal("3000.00"), result.netProfit());
        assertEquals(new BigDecimal("1000.00"), result.cashBalance());
    }

    @Test
    @DisplayName("Get Income Statement - Success")
    void getIncomeStatement_Success() {
        DateRangeRequest request = new DateRangeRequest(LocalDate.now().minusDays(30), LocalDate.now());
        
        when(ledgerAccountRepository.findAllByTypeAndIsActiveTrueOrderByCodeAsc(AccountType.REVENUE))
                .thenReturn(List.of(revenueAccount));
        when(ledgerAccountRepository.findAllByTypeAndIsActiveTrueOrderByCodeAsc(AccountType.EXPENSE))
                .thenReturn(List.of(expenseAccount));

        // Period balances
        when(journalEntryRepository.sumByAccountAndEntryType(eq(2L), eq(EntryType.DEBIT), any(), any()))
                .thenReturn(new BigDecimal("0.00"));
        when(journalEntryRepository.sumByAccountAndEntryType(eq(2L), eq(EntryType.CREDIT), any(), any()))
                .thenReturn(new BigDecimal("5000.00"));

        when(journalEntryRepository.sumByAccountAndEntryType(eq(3L), eq(EntryType.DEBIT), any(), any()))
                .thenReturn(new BigDecimal("2000.00"));
        when(journalEntryRepository.sumByAccountAndEntryType(eq(3L), eq(EntryType.CREDIT), any(), any()))
                .thenReturn(new BigDecimal("0.00"));

        IncomeStatementResponse result = accountingReportService.getIncomeStatement(request);

        assertNotNull(result);
        assertEquals(new BigDecimal("5000.00"), result.totalRevenue());
        assertEquals(new BigDecimal("2000.00"), result.totalCOGS());
        assertEquals(new BigDecimal("3000.00"), result.grossProfit());
        assertEquals(new BigDecimal("2000.00"), result.totalExpenses());
        assertEquals(new BigDecimal("3000.00"), result.netProfit());
        
        assertEquals(1, result.revenueAccounts().size());
        assertEquals(1, result.expenseAccounts().size());
    }

    @Test
    @DisplayName("Get Balance Sheet - Success")
    void getBalanceSheet_Success() {
        when(ledgerAccountRepository.findAllByTypeAndIsActiveTrueOrderByCodeAsc(AccountType.ASSET))
                .thenReturn(List.of(cashAccount));
        when(ledgerAccountRepository.findAllByTypeAndIsActiveTrueOrderByCodeAsc(AccountType.LIABILITY))
                .thenReturn(List.of(liabilityAccount));
        when(ledgerAccountRepository.findAllByTypeAndIsActiveTrueOrderByCodeAsc(AccountType.EQUITY))
                .thenReturn(new ArrayList<>());

        BalanceSheetResponse result = accountingReportService.getBalanceSheet();

        assertNotNull(result);
        assertEquals(new BigDecimal("1000.00"), result.totalAssets());
        assertEquals(new BigDecimal("500.00"), result.totalLiabilities());
        assertEquals(BigDecimal.ZERO, result.totalEquity());
    }

    @Test
    @DisplayName("Get Cash Flow - Success")
    void getCashFlow_Success() {
        DateRangeRequest request = new DateRangeRequest(LocalDate.now().minusDays(7), LocalDate.now());
        
        DailyCashFlowProjection p1 = mock(DailyCashFlowProjection.class);
        when(p1.getDate()).thenReturn(LocalDate.now());
        when(p1.getEntryType()).thenReturn(EntryType.DEBIT);
        when(p1.getTotal()).thenReturn(new BigDecimal("500.00"));

        DailyCashFlowProjection p2 = mock(DailyCashFlowProjection.class);
        when(p2.getDate()).thenReturn(LocalDate.now());
        when(p2.getEntryType()).thenReturn(EntryType.CREDIT);
        when(p2.getTotal()).thenReturn(new BigDecimal("200.00"));

        when(journalEntryRepository.getDailyCashFlows(any(), any(), any()))
                .thenReturn(Arrays.asList(p1, p2));

        CashFlowResponse result = accountingReportService.getCashFlow(request);

        assertNotNull(result);
        assertEquals(new BigDecimal("500.00"), result.totalInflows());
        assertEquals(new BigDecimal("200.00"), result.totalOutflows());
        assertEquals(new BigDecimal("300.00"), result.netCashFlow());
        assertEquals(1, result.dailyCashFlows().size());
        assertEquals(new BigDecimal("300.00"), result.dailyCashFlows().get(0).net());
    }

    @Test
    @DisplayName("Get Journal Entries - Success")
    void getJournalEntries_Success() {
        DateRangeRequest request = new DateRangeRequest(LocalDate.now().minusDays(7), LocalDate.now());
        
        JournalEntry entry = JournalEntry.builder()
                .entryNumber("JE-001")
                .description("Test")
                .type(JournalEntryType.ADJUSTMENT)
                .transactionDate(LocalDateTime.now())
                .lines(new ArrayList<>())
                .build();
        
        JournalEntryLine line = JournalEntryLine.builder()
                .account(cashAccount)
                .entryType(EntryType.DEBIT)
                .amount(new BigDecimal("100.00"))
                .journalEntry(entry)
                .build();
        entry.getLines().add(line);

        entry.setId(7L);
        LocalDateTime from = request.startDate().atStartOfDay();
        LocalDateTime toExclusive = request.endDate().plusDays(1).atStartOfDay();
        when(journalEntryRepository.findIdPageByDateRange(eq(from), eq(toExclusive), any()))
                .thenAnswer(inv -> new PageImpl<>(List.of(7L), inv.getArgument(2), 1));
        when(journalEntryRepository.findWithLinesByIdIn(List.of(7L))).thenReturn(List.of(entry));

        PageResponse<JournalEntryResponse> result = accountingReportService.getJournalEntries(request, PageRequest.of(0, 20));

        assertNotNull(result);
        assertEquals(1, result.content().size());
        assertEquals(1, result.totalElements());
        assertEquals("JE-001", result.content().get(0).entryNumber());
        assertEquals(1, result.content().get(0).lines().size());
    }

    @Test
    @DisplayName("CAT-03: journal page keeps the id page's order, clamps size, enforces the offset and date-range rules")
    void getJournalEntries_PagingRules() {
        DateRangeRequest range = new DateRangeRequest(LocalDate.of(2033, 3, 1), LocalDate.of(2033, 3, 31));
        JournalEntry a = JournalEntry.builder().id(1L).entryNumber("A").lines(new ArrayList<>()).build();
        JournalEntry b = JournalEntry.builder().id(2L).entryNumber("B").lines(new ArrayList<>()).build();
        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> asked =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        when(journalEntryRepository.findIdPageByDateRange(any(), any(), asked.capture()))
                .thenAnswer(inv -> new PageImpl<>(List.of(2L, 1L), inv.getArgument(2), 2));
        when(journalEntryRepository.findWithLinesByIdIn(List.of(2L, 1L))).thenReturn(List.of(a, b)); // unordered

        PageResponse<JournalEntryResponse> page = accountingReportService.getJournalEntries(range, PageRequest.of(3, 500));

        assertEquals(List.of("B", "A"), page.content().stream().map(JournalEntryResponse::entryNumber).toList());
        assertEquals(50, asked.getValue().getPageSize());
        assertEquals(3, asked.getValue().getPageNumber());

        com.example.perfume_budget.exception.ApiCodeException deep = assertThrows(com.example.perfume_budget.exception.ApiCodeException.class,
                () -> accountingReportService.getJournalEntries(range, PageRequest.of(200, 50)));
        assertEquals("INVALID_PAGE", deep.getCode());
        assertDoesNotThrow(() -> accountingReportService.getJournalEntries(range, PageRequest.of(199, 50)));
        assertThrows(com.example.perfume_budget.exception.ApiCodeException.class,
                () -> accountingReportService.getJournalEntries(range, PageRequest.of(1_000_001, 1)));
        com.example.perfume_budget.exception.ApiCodeException reversed = assertThrows(com.example.perfume_budget.exception.ApiCodeException.class,
                () -> accountingReportService.getJournalEntries(new DateRangeRequest(range.endDate(), range.startDate()), PageRequest.of(0, 20)));
        assertEquals("INVALID_DATE_RANGE", reversed.getCode());
    }

    @Test
    @DisplayName("Journal entries - CAT-04: years outside 1..9999 are 400 INVALID_DATE_RANGE, the edges are allowed")
    void getJournalEntries_yearsOutside1To9999_invalidDateRange() {
        java.time.LocalDate[][] refused = {
                {java.time.LocalDate.of(2033, 1, 1), java.time.LocalDate.MAX},
                {java.time.LocalDate.MIN, java.time.LocalDate.of(2033, 1, 1)},
                {java.time.LocalDate.of(2033, 1, 1), java.time.LocalDate.of(10000, 1, 1)},
                {java.time.LocalDate.of(0, 12, 31), java.time.LocalDate.of(2033, 1, 1)}};
        for (java.time.LocalDate[] r : refused) {
            com.example.perfume_budget.exception.ApiCodeException e = assertThrows(com.example.perfume_budget.exception.ApiCodeException.class,
                    () -> accountingReportService.getJournalEntries(new DateRangeRequest(r[0], r[1]), PageRequest.of(0, 20)));
            assertEquals("INVALID_DATE_RANGE", e.getCode(), r[0] + ".." + r[1]);
            assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, e.getStatus());
        }
        org.mockito.Mockito.verifyNoInteractions(journalEntryRepository);
        when(journalEntryRepository.findIdPageByDateRange(any(), any(), any())).thenReturn(org.springframework.data.domain.Page.empty());
        assertDoesNotThrow(() -> accountingReportService.getJournalEntries(
                new DateRangeRequest(java.time.LocalDate.of(1, 1, 1), java.time.LocalDate.of(9999, 12, 31)), PageRequest.of(0, 20)));
    }

    @Test
    @DisplayName("Get Manual Entries - Success")
    void getManualEntries_Success() {
        JournalEntry entry = JournalEntry.builder()
                .entryNumber("M-001")
                .isManual(true)
                .lines(new ArrayList<>())
                .build();

        when(journalEntryRepository.findByIsManualTrueOrderByTransactionDateDesc()).thenReturn(List.of(entry));

        List<JournalEntryResponse> result = accountingReportService.getManualEntries();

        assertNotNull(result);
        assertEquals(1, result.size());
        assertTrue(result.get(0).isManual());
    }

    @Test
    @DisplayName("Get Accounting Metadata - Success")
    void getAccountingMetadata_Success() {
        AccountingMetadataResponse result = accountingReportService.getAccountingMetadata();

        assertNotNull(result);
        assertFalse(result.accountCategories().isEmpty());
        assertFalse(result.journalEntryTypes().isEmpty());

        // Test formatting
        EnumResponse salesRevenue = result.accountCategories().stream()
                .filter(c -> c.value().equals("SALES_REVENUE"))
                .findFirst()
                .orElseThrow();
        assertEquals("Sales Revenue", salesRevenue.label());

        EnumResponse inventoryPurchase = result.journalEntryTypes().stream()
                .filter(t -> t.value().equals("INVENTORY_PURCHASE"))
                .findFirst()
                .orElseThrow();
        assertEquals("Inventory Purchase", inventoryPurchase.label());
    }
}
