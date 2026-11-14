package com.example.perfume_budget.isolation;

import com.example.perfume_budget.projection.ExpenseBucketProjection;
import com.example.perfume_budget.projection.RevenueAndCOGSBucketProjection;
import com.example.perfume_budget.repository.JournalEntryRepository;
import com.example.perfume_budget.support.TestTenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** TENANT-04 AC5: native SQL in JournalEntryRepository resolves unqualified tables in the current tenant schema only. */
class NativeAccountingQueryIsolationTest extends AbstractTenantIsolationTest {

    @Autowired JournalEntryRepository journalRepo;

    private static final LocalDateTime START = LocalDateTime.of(2031, 1, 1, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2031, 12, 31, 23, 59);

    private void seedEntry(TestTenant t, String entryNo, String type, String revCategory, BigDecimal amount, String entryType) {
        String s = t.schemaName();
        Long acct = jdbc.queryForObject("SELECT id FROM " + s + ".ledger_accounts WHERE category = ? LIMIT 1", Long.class, revCategory);
        jdbc.update("INSERT INTO " + s + ".journal_entries (entry_number, description, type, is_manual, transaction_date, created_at) "
                + "VALUES (?, 'qa', ?, false, '2031-06-15 10:00', now())", entryNo, type);
        Long je = jdbc.queryForObject("SELECT id FROM " + s + ".journal_entries WHERE entry_number = ?", Long.class, entryNo);
        jdbc.update("INSERT INTO " + s + ".journal_entry_lines (journal_entry_id, account_id, entry_type, amount) VALUES (?,?,?,?)",
                je, acct, entryType, amount);
    }

    @Test
    void ac5_nativeRevenueCogsAndExpenseQueriesSeeOnlyCurrentSchema() {
        TestTenant acme = tenants.acme();
        TestTenant bravo = tenants.bravo();
        seedEntry(acme, "JE-QA-ACME-REV", "SALE", "SALES_REVENUE", new BigDecimal("100.00"), "CREDIT");
        seedEntry(acme, "JE-QA-ACME-COGS", "SALE", "COGS", new BigDecimal("40.00"), "DEBIT");
        seedEntry(bravo, "JE-QA-BRAVO-REV", "SALE", "SALES_REVENUE", new BigDecimal("700.00"), "CREDIT");
        seedEntry(bravo, "JE-QA-BRAVO-COGS", "SALE", "COGS", new BigDecimal("300.00"), "DEBIT");

        List<RevenueAndCOGSBucketProjection> a = tenants.runAs(acme, () -> journalRepo.getRevenueAndCOGSByBucket(List.of("SALE"), START, END, "month"));
        List<RevenueAndCOGSBucketProjection> b = tenants.runAs(bravo, () -> journalRepo.getRevenueAndCOGSByBucket(List.of("SALE"), START, END, "month"));
        // no tenant bound -> neutral template schema, which must be empty
        List<RevenueAndCOGSBucketProjection> none = journalRepo.getRevenueAndCOGSByBucket(List.of("SALE"), START, END, "month");

        assertThat(a).hasSize(1);
        assertThat(a.get(0).getRevenue()).isEqualByComparingTo("100.00");
        assertThat(a.get(0).getCogs()).isEqualByComparingTo("40.00");
        assertThat(b).hasSize(1);
        assertThat(b.get(0).getRevenue()).isEqualByComparingTo("700.00");
        assertThat(b.get(0).getCogs()).isEqualByComparingTo("300.00");
        assertThat(none).isEmpty();
    }

    @Test
    void ac5_nativeExpenseBucketQueryIsIsolated() {
        TestTenant acme = tenants.acme();
        TestTenant bravo = tenants.bravo();
        String cat = jdbc.queryForObject("SELECT category FROM tenant_acme.ledger_accounts WHERE category LIKE '%EXPENSE%' LIMIT 1", String.class);
        seedEntry(acme, "JE-QA-ACME-EXP", "ADJUSTMENT", cat, new BigDecimal("11.00"), "DEBIT");
        seedEntry(bravo, "JE-QA-BRAVO-EXP", "ADJUSTMENT", cat, new BigDecimal("22.00"), "DEBIT");

        List<ExpenseBucketProjection> a = tenants.runAs(acme, () -> journalRepo.getExpensesByBucket(List.of(cat), START, END, "month"));
        List<ExpenseBucketProjection> b = tenants.runAs(bravo, () -> journalRepo.getExpensesByBucket(List.of(cat), START, END, "month"));
        assertThat(a).hasSize(1);
        assertThat(a.get(0).getExpenses()).isEqualByComparingTo("11.00");
        assertThat(b).hasSize(1);
        assertThat(b.get(0).getExpenses()).isEqualByComparingTo("22.00");
    }

    @Test
    void ac5_jpqlJournalQueriesAreIsolatedToo() {
        TestTenant acme = tenants.acme();
        TestTenant bravo = tenants.bravo();
        seedEntry(acme, "JE-QA-ACME-JPQL", "SALE", "SALES_REVENUE", new BigDecimal("1.00"), "CREDIT");
        seedEntry(bravo, "JE-QA-BRAVO-JPQL", "SALE", "SALES_REVENUE", new BigDecimal("2.00"), "CREDIT");
        var a = tenants.runAs(acme, () -> journalRepo.findAllByDateRange(START, END));
        var b = tenants.runAs(bravo, () -> journalRepo.findAllByDateRange(START, END));
        assertThat(a).extracting("entryNumber").contains("JE-QA-ACME-JPQL").doesNotContain("JE-QA-BRAVO-JPQL");
        assertThat(b).extracting("entryNumber").contains("JE-QA-BRAVO-JPQL").doesNotContain("JE-QA-ACME-JPQL");
    }
}
