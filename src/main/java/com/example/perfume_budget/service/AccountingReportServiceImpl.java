package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.accounts.request.DateRangeRequest;
import com.example.perfume_budget.exception.ApiCodeException;
import com.example.perfume_budget.dto.accounts.response.*;
import com.example.perfume_budget.enums.AccountCategory;
import com.example.perfume_budget.enums.AccountType;
import com.example.perfume_budget.enums.EntryType;
import com.example.perfume_budget.enums.JournalEntryType;
import com.example.perfume_budget.model.JournalEntry;
import com.example.perfume_budget.model.LedgerAccount;
import com.example.perfume_budget.projection.DailyCashFlowProjection;
import com.example.perfume_budget.repository.JournalEntryRepository;
import com.example.perfume_budget.repository.LedgerAccountRepository;
import com.example.perfume_budget.service.interfaces.AccountingReportService;
import com.example.perfume_budget.utils.PaginationUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountingReportServiceImpl implements AccountingReportService {

    private final JournalEntryRepository journalEntryRepository;
    private final LedgerAccountRepository ledgerAccountRepository;

    @Override
    public LedgerSummaryResponse getLedgerSummary() {
        List<LedgerAccount> allAccounts = ledgerAccountRepository
                .findAllByIsActiveTrueOrderByCodeAsc();

        BigDecimal totalRevenue = sumByType(allAccounts, AccountType.REVENUE);
        BigDecimal totalExpenses = sumByType(allAccounts, AccountType.EXPENSE);
        BigDecimal totalAssets = sumByType(allAccounts, AccountType.ASSET);
        BigDecimal totalLiabilities = sumByType(allAccounts, AccountType.LIABILITY);
        BigDecimal cashBalance = allAccounts.stream()
                .filter(a -> a.getCategory() == AccountCategory.CASH)
                .map(LedgerAccount::getBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new LedgerSummaryResponse(
                totalRevenue,
                totalExpenses,
                totalAssets,
                totalLiabilities,
                totalRevenue.subtract(totalExpenses),
                cashBalance
        );
    }

    @Override
    public IncomeStatementResponse getIncomeStatement(DateRangeRequest dateRange) {
        requireValidRange(dateRange);
        LocalDateTime start = dateRange.startDate().atStartOfDay();
        LocalDateTime end = dateRange.endDate().atTime(23, 59, 59);

        List<LedgerAccount> revenueAccounts = ledgerAccountRepository
                .findAllByTypeAndIsActiveTrueOrderByCodeAsc(AccountType.REVENUE);
        List<LedgerAccount> expenseAccounts = ledgerAccountRepository
                .findAllByTypeAndIsActiveTrueOrderByCodeAsc(AccountType.EXPENSE);

        // calculate period balances from journal entries
        List<AccountBalanceResponse> revenueDetails = revenueAccounts.stream()
                .map(account -> new AccountBalanceResponse(
                        account.getCode(),
                        account.getName(),
                        account.getType(),
                        account.getCategory(),
                        getPeriodBalance(account, start, end)
                ))
                .filter(a -> a.balance().compareTo(BigDecimal.ZERO) > 0)
                .toList();

        List<AccountBalanceResponse> expenseDetails = expenseAccounts.stream()
                .map(account -> new AccountBalanceResponse(
                        account.getCode(),
                        account.getName(),
                        account.getType(),
                        account.getCategory(),
                        getPeriodBalance(account, start, end)
                ))
                .filter(a -> a.balance().compareTo(BigDecimal.ZERO) > 0)
                .toList();

        BigDecimal totalRevenue = revenueDetails.stream()
                .map(AccountBalanceResponse::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalExpenses = expenseDetails.stream()
                .map(AccountBalanceResponse::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal cogs = expenseAccounts.stream()
                .filter(a -> a.getCategory() == AccountCategory.COGS)
                .map(a -> getPeriodBalance(a, start, end))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal grossProfit = totalRevenue.subtract(cogs);
        BigDecimal netProfit = totalRevenue.subtract(totalExpenses);

        return new IncomeStatementResponse(
                totalRevenue,
                cogs,
                grossProfit,
                totalExpenses,
                netProfit,
                revenueDetails,
                expenseDetails
        );
    }

    @Override
    public BalanceSheetResponse getBalanceSheet() {
        List<AccountBalanceResponse> assets = getAccountsByType(AccountType.ASSET);
        List<AccountBalanceResponse> liabilities = getAccountsByType(AccountType.LIABILITY);

        // calculate retained earnings dynamically
        List<LedgerAccount> allAccounts = ledgerAccountRepository
                .findAllByIsActiveTrueOrderByCodeAsc();
        BigDecimal totalRevenue = sumByType(allAccounts, AccountType.REVENUE);
        BigDecimal totalExpenses = sumByType(allAccounts, AccountType.EXPENSE);
        BigDecimal retainedEarnings = totalRevenue.subtract(totalExpenses);

        // build the equity list and inject retained earnings as a virtual entry
        List<AccountBalanceResponse> equity = new ArrayList<>(getAccountsByType(AccountType.EQUITY));
        equity.add(new AccountBalanceResponse(
                "3200",
                "Retained Earnings",
                AccountType.EQUITY,
                AccountCategory.RETAINED_EARNINGS,
                retainedEarnings
        ));

        BigDecimal totalAssets = assets.stream()
                .map(AccountBalanceResponse::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalLiabilities = liabilities.stream()
                .map(AccountBalanceResponse::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalEquity = equity.stream()
                .map(AccountBalanceResponse::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new BalanceSheetResponse(
                totalAssets,
                totalLiabilities,
                totalEquity,
                assets,
                liabilities,
                equity
        );
    }

    @Override
    public CashFlowResponse getCashFlow(DateRangeRequest dateRange) {
        requireValidRange(dateRange);
        LocalDateTime start = dateRange.startDate().atStartOfDay();
        LocalDateTime end = dateRange.endDate().atTime(23, 59, 59);

        List<DailyCashFlowProjection> projections = journalEntryRepository
                .getDailyCashFlows(AccountCategory.CASH, start, end);

        // group by date and build daily cash flows
        Map<LocalDate, List<DailyCashFlowProjection>> groupedByDate = projections.stream()
                .collect(Collectors.groupingBy(DailyCashFlowProjection::getDate));

        List<DailyCashFlow> dailyCashFlows = groupedByDate.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    BigDecimal inflow = entry.getValue().stream()
                            .filter(p -> p.getEntryType() == EntryType.DEBIT)
                            .map(DailyCashFlowProjection::getTotal)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    BigDecimal outflow = entry.getValue().stream()
                            .filter(p -> p.getEntryType() == EntryType.CREDIT)
                            .map(DailyCashFlowProjection::getTotal)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    return new DailyCashFlow(
                            entry.getKey(),
                            inflow,
                            outflow,
                            inflow.subtract(outflow)
                    );
                })
                .toList();

        BigDecimal totalInflows = dailyCashFlows.stream()
                .map(DailyCashFlow::inflow)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalOutflows = dailyCashFlows.stream()
                .map(DailyCashFlow::outflow)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new CashFlowResponse(
                totalInflows,
                totalOutflows,
                totalInflows.subtract(totalOutflows),
                dailyCashFlows
        );
    }

    @Override
    public PageResponse<JournalEntryResponse> getJournalEntries(DateRangeRequest dateRange, Pageable pageable) {
        requireValidRange(dateRange);
        Pageable page = journalPage(pageable);
        // both days whole: [startDate 00:00, endDate + 1 day 00:00) (was BETWEEN ... endDate 23:59:59, which lost the
        // entries of the last second's fractions)
        LocalDateTime start = dateRange.startDate().atStartOfDay();
        LocalDateTime end = dateRange.endDate().plusDays(1).atStartOfDay();

        Page<Long> ids = journalEntryRepository.findIdPageByDateRange(start, end, page);
        Map<Long, JournalEntry> byId = ids.isEmpty() ? Map.of()
                : journalEntryRepository.findWithLinesByIdIn(ids.getContent()).stream()
                        .collect(Collectors.toMap(JournalEntry::getId, e -> e));
        return PaginationUtil.createPageResponse(ids.map(id -> toJournalEntryResponse(byId.get(id))));
    }

    /**
     * CAT-04 / CAT-06: one date rule for every ranged accounting route (journal entries, income statement, cash flow).
     * ISO dates parse up to year +/-999,999,999; outside 1..9999 the end + 1 day overflowed (500) or Postgres could not bind
     * the timestamp (409). No ledger entry can be outside that range anyway. Not-real dates (2026-02-30) never get here:
     * the controller's strict ISO conversion answers 400 first.
     */
    static void requireValidRange(DateRangeRequest dateRange) {
        if (outsideYears1To9999(dateRange.startDate()) || outsideYears1To9999(dateRange.endDate())) {
            throw new ApiCodeException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE",
                    "'startDate' and 'endDate' must be between 0001-01-01 and 9999-12-31.");
        }
        if (dateRange.startDate().isAfter(dateRange.endDate())) {
            throw new ApiCodeException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "'startDate' must not be after 'endDate'.");
        }
    }

    private static boolean outsideYears1To9999(LocalDate date) {
        return date.getYear() < 1 || date.getYear() > 9999;
    }

    /**
     * CAT-03: the admin order list's paging rules (OrderServiceImpl: MAX_PAGE, MAX_PAGE_SIZE, MAX_OFFSET), so the
     * journal cannot be read in one unbounded response. The order is fixed (the query's newest first, id tie-break); a
     * {@code sort} parameter is ignored, like the order audit-events list.
     */
    private static Pageable journalPage(Pageable pageable) {
        if (pageable.getPageNumber() > OrderServiceImpl.MAX_PAGE) {
            throw new ApiCodeException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "page must be <= " + OrderServiceImpl.MAX_PAGE + ".");
        }
        int size = Math.min(pageable.getPageSize(), OrderServiceImpl.MAX_PAGE_SIZE);
        if ((long) pageable.getPageNumber() * size >= OrderServiceImpl.MAX_OFFSET) {
            throw new ApiCodeException(HttpStatus.BAD_REQUEST, "INVALID_PAGE",
                    "page * size must be below " + OrderServiceImpl.MAX_OFFSET + ": narrow the date range instead.",
                    Map.of("maxOffset", OrderServiceImpl.MAX_OFFSET, "size", size));
        }
        return PageRequest.of(pageable.getPageNumber(), size);
    }

    @Override
    public List<JournalEntryResponse> getJournalEntriesByReference(
            String referenceType, String referenceId) {
        return journalEntryRepository
                .findByReferenceTypeAndReferenceId(referenceType, referenceId)
                .stream()
                .map(this::toJournalEntryResponse)
                .toList();
    }

    @Override
    public List<JournalEntryResponse> getManualEntries() {
        return journalEntryRepository.findByIsManualTrueOrderByTransactionDateDesc()
                .stream()
                .map(this::toJournalEntryResponse)
                .toList();
    }

    @Override
    public AccountingMetadataResponse getAccountingMetadata() {
        List<EnumResponse> categories = Arrays.stream(AccountCategory.values())
                .map(category -> new EnumResponse(category.name(), formatEnumName(category.name())))
                .toList();

        List<EnumResponse> types = Arrays.stream(JournalEntryType.values())
                .map(type -> new EnumResponse(type.name(), formatEnumName(type.name())))
                .toList();

        return new AccountingMetadataResponse(categories, types);
    }

    // HELPER METHODS
    private String formatEnumName(String name) {
        if (name == null || name.isEmpty()) return "";
        String[] words = name.toLowerCase().split("_");
        return Arrays.stream(words)
                .map(word -> word.substring(0, 1).toUpperCase() + word.substring(1))
                .collect(Collectors.joining(" "));
    }

    private BigDecimal getPeriodBalance(LedgerAccount account,
                                        LocalDateTime start, LocalDateTime end) {
        boolean isDebitNormal = account.getType() == AccountType.ASSET
                || account.getType() == AccountType.EXPENSE;

        BigDecimal debits = journalEntryRepository.sumByAccountAndEntryType(
                account.getId(), EntryType.DEBIT, start, end);
        BigDecimal credits = journalEntryRepository.sumByAccountAndEntryType(
                account.getId(), EntryType.CREDIT, start, end);

        return isDebitNormal
                ? debits.subtract(credits)
                : credits.subtract(debits);
    }

    private List<AccountBalanceResponse> getAccountsByType(AccountType type) {
        return ledgerAccountRepository
                .findAllByTypeAndIsActiveTrueOrderByCodeAsc(type)
                .stream()
                .map(account -> new AccountBalanceResponse(
                        account.getCode(),
                        account.getName(),
                        account.getType(),
                        account.getCategory(),
                        account.getBalance()
                ))
                .toList();
    }

    private BigDecimal sumByType(List<LedgerAccount> accounts, AccountType type) {
        return accounts.stream()
                .filter(a -> a.getType() == type)
                .map(LedgerAccount::getBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private JournalEntryResponse toJournalEntryResponse(JournalEntry entry) {
        List<JournalEntryLineResponse> lines = entry.getLines().stream()
                .map(line -> new JournalEntryLineResponse(
                        line.getAccount().getCode(),
                        line.getAccount().getName(),
                        line.getEntryType(),
                        line.getAmount(),
                        line.getDescription()
                ))
                .toList();

        return new JournalEntryResponse(
                entry.getEntryNumber(),
                entry.getDescription(),
                entry.getType(),
                entry.getReferenceType(),
                entry.getReferenceId(),
                entry.getIsManual(),
                entry.getRecordedBy(),
                entry.getTransactionDate(),
                lines
        );
    }
}
