package com.example.perfume_budget.repository;

import com.example.perfume_budget.enums.AccountCategory;
import com.example.perfume_budget.enums.EntryType;
import com.example.perfume_budget.enums.JournalEntryType;
import com.example.perfume_budget.model.JournalEntry;
import com.example.perfume_budget.projection.DailyCashFlowProjection;
import com.example.perfume_budget.projection.RevenueAndCOGSBucketProjection;
import com.example.perfume_budget.projection.ExpenseBucketProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {
    // entries within a date range
    @Query("""
            SELECT j FROM JournalEntry j
            WHERE j.transactionDate BETWEEN :start AND :end
            ORDER BY j.transactionDate DESC
            """)
    List<JournalEntry> findAllByDateRange(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    // entries by type within a date range
    @Query("""
            SELECT j FROM JournalEntry j
            WHERE j.type = :type
            AND j.transactionDate BETWEEN :start AND :end
            ORDER BY j.transactionDate DESC
            """)
    List<JournalEntry> findByTypeAndDateRange(
            @Param("type") JournalEntryType type,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    // manual entries only
    List<JournalEntry> findByIsManualTrueOrderByTransactionDateDesc();

    // entries by reference
    List<JournalEntry> findByReferenceTypeAndReferenceId(
            String referenceType, String referenceId);

    // total debits for an account within a date range
    @Query("""
            SELECT COALESCE(SUM(l.amount), 0)
            FROM JournalEntryLine l
            JOIN l.journalEntry j
            WHERE l.account.id = :accountId
            AND l.entryType = :entryType
            AND j.transactionDate BETWEEN :start AND :end
            """)
    BigDecimal sumByAccountAndEntryType(
            @Param("accountId") Long accountId,
            @Param("entryType") EntryType entryType,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    // daily cash flows
    @Query("""
            SELECT CAST(j.transactionDate AS date) AS date,
                   l.entryType AS entryType,
                   COALESCE(SUM(l.amount), 0) AS total
            FROM JournalEntryLine l
            JOIN l.journalEntry j
            WHERE l.account.category = :category
            AND j.transactionDate BETWEEN :start AND :end
            GROUP BY CAST(j.transactionDate AS date), l.entryType
            ORDER BY CAST(j.transactionDate AS date) ASC
            """)
    List<DailyCashFlowProjection> getDailyCashFlows(
            @Param("category") AccountCategory category,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    /**
     * Query A: revenue + COGS per time bucket, filtered by journal entry types (source).
     * granularity must be one of: 'day', 'week', 'month', 'year'
     * types must be one or both of: 'SALE', 'WALK_IN_SALE'
     */
    @Query(value = """
            SELECT DATE_TRUNC(:granularity, j.transaction_date) AS bucket_start,
                   COALESCE(SUM(CASE WHEN a.category = 'SALES_REVENUE' AND l.entry_type = 'CREDIT'
                                     THEN l.amount ELSE 0 END), 0) AS revenue,
                   COALESCE(SUM(CASE WHEN a.category = 'COGS' AND l.entry_type = 'DEBIT'
                                     THEN l.amount ELSE 0 END), 0) AS cogs
            FROM journal_entries j
            JOIN journal_entry_lines l ON l.journal_entry_id = j.id
            JOIN ledger_accounts a ON a.id = l.account_id
            WHERE j.type IN (:types)
              AND j.transaction_date BETWEEN :start AND :end
            GROUP BY 1
            ORDER BY 1
            """, nativeQuery = true)
    List<RevenueAndCOGSBucketProjection> getRevenueAndCOGSByBucket(
            @Param("types") List<String> types,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("granularity") String granularity);

    /**
     * Query B: operating expense debits per time bucket — no source filter, always ALL.
     * expenseCategories: list of category names e.g. 'DISCOUNT_EXPENSE', 'MARKETING_EXPENSE' etc.
     * granularity must be one of: 'day', 'week', 'month', 'year'
     */
    @Query(value = """
            SELECT DATE_TRUNC(:granularity, j.transaction_date) AS bucket_start,
                   COALESCE(SUM(l.amount), 0) AS expenses
            FROM journal_entries j
            JOIN journal_entry_lines l ON l.journal_entry_id = j.id
            JOIN ledger_accounts a ON a.id = l.account_id
            WHERE a.category IN (:expenseCategories)
              AND l.entry_type = 'DEBIT'
              AND j.transaction_date BETWEEN :start AND :end
            GROUP BY 1
            ORDER BY 1
            """, nativeQuery = true)
    List<ExpenseBucketProjection> getExpensesByBucket(
            @Param("expenseCategories") List<String> expenseCategories,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("granularity") String granularity);
}
