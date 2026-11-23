package com.example.perfume_budget.repository;

import com.example.perfume_budget.enums.AccountCategory;
import com.example.perfume_budget.enums.EntryType;
import com.example.perfume_budget.enums.JournalEntryType;
import com.example.perfume_budget.model.JournalEntry;
import com.example.perfume_budget.projection.DailyCashFlowProjection;
import com.example.perfume_budget.projection.RevenueAndCOGSBucketProjection;
import com.example.perfume_budget.projection.ExpenseBucketProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
/*
 * CAT-02: the journal-entry report reads map every line and its account outside a transaction (open-in-view is off), so
 * those reads load lines + accounts in the same query (entity graph; one row per entry, no N+1).
 */
public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {
    /**
     * CAT-03: one page of the ids of the entries with {@code start <= transactionDate < end}, newest first with the id as
     * the tie-break (stable across pages). Ids only: paging a fetch join of the lines collection would make Hibernate
     * page in memory; the page's entries are loaded with {@link #findWithLinesByIdIn}.
     */
    @Query(value = """
            SELECT j.id FROM JournalEntry j
            WHERE j.transactionDate >= :start AND j.transactionDate < :end
            ORDER BY j.transactionDate DESC, j.id DESC
            """,
            countQuery = """
            SELECT count(j) FROM JournalEntry j
            WHERE j.transactionDate >= :start AND j.transactionDate < :end
            """)
    Page<Long> findIdPageByDateRange(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            Pageable pageable);

    /** CAT-03: the given entries with their lines and accounts (one query, unordered: the caller orders by its id page). */
    @EntityGraph(attributePaths = {"lines", "lines.account"})
    @Query("SELECT DISTINCT j FROM JournalEntry j WHERE j.id IN :ids")
    List<JournalEntry> findWithLinesByIdIn(@Param("ids") Collection<Long> ids);

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

    /** API-RD-12d: the manual entry an admin already recorded under an Idempotency-Key. */
    java.util.Optional<JournalEntry> findByIdempotencyUserIdAndIdempotencyKey(Long idempotencyUserId, String idempotencyKey);

    // manual entries only
    @EntityGraph(attributePaths = {"lines", "lines.account"})
    List<JournalEntry> findByIsManualTrueOrderByTransactionDateDesc();

    // entries by reference
    @EntityGraph(attributePaths = {"lines", "lines.account"})
    List<JournalEntry> findByReferenceTypeAndReferenceId(
            String referenceType, String referenceId);

    // API-RD-12a: total for an account in [start, end): end is the start of the day AFTER the last day, like the journal list
    // total debits for an account within a date range
    @Query("""
            SELECT COALESCE(SUM(l.amount), 0)
            FROM JournalEntryLine l
            JOIN l.journalEntry j
            WHERE l.account.id = :accountId
            AND l.entryType = :entryType
            AND j.transactionDate >= :start AND j.transactionDate < :end
            """)
    BigDecimal sumByAccountAndEntryType(
            @Param("accountId") Long accountId,
            @Param("entryType") EntryType entryType,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    // daily cash flows over [start, end) (API-RD-12a)
    @Query("""
            SELECT CAST(j.transactionDate AS date) AS date,
                   l.entryType AS entryType,
                   COALESCE(SUM(l.amount), 0) AS total
            FROM JournalEntryLine l
            JOIN l.journalEntry j
            WHERE l.account.category = :category
            AND j.transactionDate >= :start AND j.transactionDate < :end
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
     * types: 'SALE', 'WALK_IN_SALE', 'SALE_REVERSAL'. ORD-02: net amounts (revenue credits minus debits, COGS debits
     * minus credits), so a SALE_REVERSAL dated at the cancel takes the sale back out in that bucket.
     */
    @Query(value = """
            SELECT DATE_TRUNC(:granularity, j.transaction_date) AS bucket_start,
                   COALESCE(SUM(CASE WHEN a.category = 'SALES_REVENUE' AND l.entry_type = 'CREDIT' THEN l.amount
                                     WHEN a.category = 'SALES_REVENUE' AND l.entry_type = 'DEBIT' THEN -l.amount
                                     ELSE 0 END), 0) AS revenue,
                   COALESCE(SUM(CASE WHEN a.category = 'COGS' AND l.entry_type = 'DEBIT' THEN l.amount
                                     WHEN a.category = 'COGS' AND l.entry_type = 'CREDIT' THEN -l.amount
                                     ELSE 0 END), 0) AS cogs
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
     * Query B: operating expenses per time bucket — no source filter, always ALL. ORD-02: net (debits minus credits),
     * so the discount given on a cancelled paid order (credited back by its SALE_REVERSAL) no longer counts.
     * expenseCategories: list of category names e.g. 'DISCOUNT_EXPENSE', 'MARKETING_EXPENSE' etc.
     * granularity must be one of: 'day', 'week', 'month', 'year'
     */
    @Query(value = """
            SELECT DATE_TRUNC(:granularity, j.transaction_date) AS bucket_start,
                   COALESCE(SUM(CASE WHEN l.entry_type = 'DEBIT' THEN l.amount ELSE -l.amount END), 0) AS expenses
            FROM journal_entries j
            JOIN journal_entry_lines l ON l.journal_entry_id = j.id
            JOIN ledger_accounts a ON a.id = l.account_id
            WHERE a.category IN (:expenseCategories)
              AND j.transaction_date BETWEEN :start AND :end
            GROUP BY 1
            ORDER BY 1
            """, nativeQuery = true)
    List<ExpenseBucketProjection> getExpensesByBucket(
            @Param("expenseCategories") List<String> expenseCategories,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("granularity") String granularity);

    /** ORD-02: the automatic entries of one online order of a type (referenceType ORDER), oldest first. */
    List<JournalEntry> findByReferenceTypeAndReferenceIdAndTypeOrderByIdAsc(
            String referenceType, String referenceId, JournalEntryType type);

    /** ORD-02: number of lines of the order's automatic entries of a type that hit an account category. */
    @Query("""
            SELECT COUNT(l.id)
            FROM JournalEntryLine l
            JOIN l.journalEntry j
            WHERE j.referenceType = 'ORDER'
            AND j.referenceId = :orderNumber
            AND j.type = :type
            AND l.account.category = :category
            """)
    long countOrderLines(@Param("orderNumber") String orderNumber,
                         @Param("type") JournalEntryType type,
                         @Param("category") AccountCategory category);

    /** ORD-02: sum of the order's automatic lines of a type on one account category and side. */
    @Query("""
            SELECT COALESCE(SUM(l.amount), 0)
            FROM JournalEntryLine l
            JOIN l.journalEntry j
            WHERE j.referenceType = 'ORDER'
            AND j.referenceId = :orderNumber
            AND j.type = :type
            AND l.account.category = :category
            AND l.entryType = :entryType
            """)
    BigDecimal sumOrderLines(@Param("orderNumber") String orderNumber,
                             @Param("type") JournalEntryType type,
                             @Param("category") AccountCategory category,
                             @Param("entryType") EntryType entryType);

    /** ORD-02b: like {@link #sumOrderLines} over several entry types (e.g. everything that put money into Refunds Payable). */
    @Query("""
            SELECT COALESCE(SUM(l.amount), 0)
            FROM JournalEntryLine l
            JOIN l.journalEntry j
            WHERE j.referenceType = 'ORDER'
            AND j.referenceId = :orderNumber
            AND j.type IN :types
            AND l.account.category = :category
            AND l.entryType = :entryType
            """)
    BigDecimal sumOrderLinesOfTypes(@Param("orderNumber") String orderNumber,
                                    @Param("types") java.util.Collection<JournalEntryType> types,
                                    @Param("category") AccountCategory category,
                                    @Param("entryType") EntryType entryType);
}
