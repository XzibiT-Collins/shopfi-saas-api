package com.example.perfume_budget.handlers;

import com.example.perfume_budget.outbox.OutboxKind;
import com.example.perfume_budget.outbox.OutboxStore;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EVT-01b test double of {@link OutboxStore}: same state machine in memory. A row recorded inside a transaction is
 * removed again if that transaction rolls back (the real row is part of the publishing transaction). "Due" is a flag
 * the test sets ({@link #makeDue}) instead of a clock.
 */
class InMemoryOutboxStore extends OutboxStore {

    static final class Entry {
        final long id;
        final OutboxKind kind;
        final String reference;
        String status = "PENDING";
        int attempts;
        boolean due;
        String lastError;

        Entry(long id, OutboxKind kind, String reference) {
            this.id = id;
            this.kind = kind;
            this.reference = reference;
        }

        Row row() {
            return new Row(id, kind, reference, attempts);
        }
    }

    private final AtomicLong ids = new AtomicLong();
    final Map<String, Entry> rows = new LinkedHashMap<>();

    /** No persistence context: overriding the setter (without the annotation) keeps it from being injected. */
    @Override
    public void setEntityManager(jakarta.persistence.EntityManager em) {
    }

    @Override
    public synchronized void record(OutboxKind kind, Object reference) {
        String key = kind + ":" + reference;
        if (rows.containsKey(key)) {
            return;
        }
        rows.put(key, new Entry(ids.incrementAndGet(), kind, String.valueOf(reference)));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        synchronized (InMemoryOutboxStore.this) {
                            rows.remove(key);
                        }
                    }
                }
            });
        }
    }

    @Override
    public synchronized Optional<Row> claimFresh(OutboxKind kind, String reference) {
        Entry e = rows.get(kind + ":" + reference);
        if (e == null || !e.status.equals("PENDING") || e.attempts != 0) {
            return Optional.empty();
        }
        e.attempts++;
        e.due = false;
        return Optional.of(e.row());
    }

    @Override
    public synchronized Optional<Row> claim(Row reserved) {
        for (Entry e : rows.values()) {
            if (e.id == reserved.id() && e.status.equals("PENDING") && e.attempts == reserved.attempts()) {
                e.attempts++;
                e.due = false;
                return Optional.of(e.row());
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized List<Row> reserveDue(int limit) {
        List<Row> out = new ArrayList<>();
        for (Entry e : rows.values()) {
            if (e.status.equals("PENDING") && e.due && out.size() < limit) {
                e.due = false;
                out.add(e.row());
            }
        }
        return out;
    }

    @Override
    public synchronized int release(Collection<Row> released) {
        int n = 0;
        for (Row r : released) {
            for (Entry e : rows.values()) {
                if (e.id == r.id() && e.status.equals("PENDING") && e.attempts == r.attempts()) {
                    e.due = true;
                    n++;
                }
            }
        }
        return n;
    }

    @Override
    public synchronized boolean complete(Row claimed) {
        for (Entry e : rows.values()) {
            if (e.id == claimed.id() && e.status.equals("PENDING") && e.attempts == claimed.attempts()) {
                e.status = "DONE";
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized FailOutcome fail(Row claimed, String error, boolean retryable) {
        for (Entry e : rows.values()) {
            if (e.id == claimed.id() && e.status.equals("PENDING") && e.attempts == claimed.attempts()) {
                e.lastError = error;
                e.status = e.attempts >= MAX_ATTEMPTS || !retryable ? "FAILED" : "PENDING";
                return e.status.equals("FAILED") ? FailOutcome.FAILED : FailOutcome.RETRY;
            }
        }
        return FailOutcome.STALE;
    }

    @Override
    public int purgeDone(Duration retention) {
        return 0;
    }

    synchronized void makeDue() {
        rows.values().forEach(e -> e.due = true);
    }

    synchronized Entry get(OutboxKind kind, Object reference) {
        return rows.get(kind + ":" + reference);
    }
}
