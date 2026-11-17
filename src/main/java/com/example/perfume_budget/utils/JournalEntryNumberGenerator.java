package com.example.perfume_budget.utils;

import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

@Component
public class JournalEntryNumberGenerator {
    /**
     * "JE" + epoch millis + "-" + 8 random hex digits. ORD-02 rework: the bare millisecond number collided
     * (uc_journal_entries_entrynumber) when two postings ran in the same millisecond, e.g. concurrent paid cancels,
     * refund acknowledgements and payment webhooks, failing one of them.
     */
    public String generate() {
        return "JE" + System.currentTimeMillis() + "-"
                + String.format("%08x", ThreadLocalRandom.current().nextInt());
    }
}
