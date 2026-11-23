package com.example.perfume_budget.utils;

import com.example.perfume_budget.dto.accounts.request.JournalEntryLineRequest;
import com.example.perfume_budget.dto.accounts.request.ManualJournalEntryRequest;
import com.example.perfume_budget.enums.AccountCategory;
import com.example.perfume_budget.enums.EntryType;
import com.example.perfume_budget.enums.JournalEntryType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ManualEntryFingerprintTest {

    private static ManualJournalEntryRequest req(String desc, JournalEntryType type, AccountCategory cat, EntryType side,
                                                 String amount, String lineDesc) {
        return new ManualJournalEntryRequest(desc, type, List.of(
                new JournalEntryLineRequest(cat, side, new BigDecimal(amount), lineDesc),
                new JournalEntryLineRequest(AccountCategory.OWNERS_CAPITAL, EntryType.CREDIT, new BigDecimal(amount), "c")));
    }

    private static String fp(ManualJournalEntryRequest r) {
        return ManualEntryFingerprint.of(r);
    }

    private final ManualJournalEntryRequest base = req("d", JournalEntryType.EXPENSE, AccountCategory.CASH, EntryType.DEBIT, "5.00", "l");

    @Test
    void everyFieldChangesTheFingerprint() {
        assertThat(fp(req("d2", JournalEntryType.EXPENSE, AccountCategory.CASH, EntryType.DEBIT, "5.00", "l"))).isNotEqualTo(fp(base));
        assertThat(fp(req("d", JournalEntryType.ADJUSTMENT, AccountCategory.CASH, EntryType.DEBIT, "5.00", "l"))).isNotEqualTo(fp(base));
        assertThat(fp(req("d", JournalEntryType.EXPENSE, AccountCategory.MOBILE_MONEY, EntryType.DEBIT, "5.00", "l"))).isNotEqualTo(fp(base));
        assertThat(fp(req("d", JournalEntryType.EXPENSE, AccountCategory.CASH, EntryType.CREDIT, "5.00", "l"))).isNotEqualTo(fp(base));
        assertThat(fp(req("d", JournalEntryType.EXPENSE, AccountCategory.CASH, EntryType.DEBIT, "5.01", "l"))).isNotEqualTo(fp(base));
        assertThat(fp(req("d", JournalEntryType.EXPENSE, AccountCategory.CASH, EntryType.DEBIT, "5.00", "l2"))).isNotEqualTo(fp(base));
    }

    @Test
    void scaleLineOrderAndBlankDescriptionDoNotMatter() {
        assertThat(fp(req("d", JournalEntryType.EXPENSE, AccountCategory.CASH, EntryType.DEBIT, "5", "l"))).isEqualTo(fp(base));
        ManualJournalEntryRequest swapped = new ManualJournalEntryRequest("d", JournalEntryType.EXPENSE,
                List.of(base.lines().get(1), base.lines().get(0)));
        assertThat(fp(swapped)).isEqualTo(fp(base));
        assertThat(fp(req("d", JournalEntryType.EXPENSE, AccountCategory.CASH, EntryType.DEBIT, "5.00", null)))
                .isEqualTo(fp(req("d", JournalEntryType.EXPENSE, AccountCategory.CASH, EntryType.DEBIT, "5.00", "  ")));
    }
}
