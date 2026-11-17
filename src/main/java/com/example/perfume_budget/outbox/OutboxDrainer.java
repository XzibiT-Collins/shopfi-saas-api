package com.example.perfume_budget.outbox;

import com.example.perfume_budget.handlers.MailDispatcher;
import com.example.perfume_budget.tenancy.TenantRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * EVT-01b (L2): re-drives the side-effect outbox of every active tenant: rows whose immediate hand-off was refused
 * (executor full or shut down), whose worker died, or whose send failed and is due for a retry. Rows are handed to
 * the same bounded executors without waiting. DONE rows are purged after {@link #RETENTION}; FAILED rows stay for an
 * operator.
 *
 * <p>EVT-01c (7): runs on its own thread ({@link OutboxDrainScheduler}), not on the shared {@code @Scheduled} thread.
 * Each row is handled on its own: a refused hand-off releases only the rows of the executor that refused (fenced on
 * their reserved attempts, {@link OutboxStore#release}); rows for the other executor are still handed off. A drain
 * costs one short transaction per tenant (the reservation) plus one release when something was refused; the purge
 * runs at most once per {@link #PURGE_EVERY} per tenant round.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxDrainer {

    static final int BATCH = 100;
    static final Duration RETENTION = Duration.ofDays(14);
    static final Duration PURGE_EVERY = Duration.ofHours(1);

    private final OutboxStore store;
    private final OutboxProcessor processor;
    private final MailDispatcher mailDispatcher;
    private final TenantRunner tenantRunner;
    private Clock clock = Clock.systemUTC();
    private volatile Instant lastPurge = Instant.MIN;

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** One drain round over every active tenant (called by {@link OutboxDrainScheduler}; tests call it directly). */
    public void drainAllTenants() {
        Instant now = clock.instant();
        boolean purge = !now.isBefore(lastPurge.plus(PURGE_EVERY));
        tenantRunner.forEachActiveTenant(() -> drain(purge));
        if (purge) {
            lastPurge = now;
        }
    }

    /** One drain of the current tenant's outbox, purge included. @return how many rows were handed off */
    public int drainCurrentTenant() {
        return drain(true);
    }

    private int drain(boolean purge) {
        if (purge) {
            store.purgeDone(RETENTION);
        }
        List<OutboxStore.Row> due = store.reserveDue(BATCH);
        int handedOff = 0;
        boolean mailRefused = false;
        boolean notificationRefused = false;
        List<OutboxStore.Row> refused = new ArrayList<>();
        for (OutboxStore.Row row : due) {
            boolean mail = row.kind().mail();
            // once an executor refused in this drain, its other rows wait for the next drain (no hand-off storm)
            if ((mail ? mailRefused : notificationRefused)
                    || !mailDispatcher.handOff(row.kind(), row.describe(), () -> processor.runReserved(row))) {
                refused.add(row);
                if (mail) {
                    mailRefused = true;
                } else {
                    notificationRefused = true;
                }
                continue;
            }
            handedOff++;
        }
        if (!refused.isEmpty()) {
            store.release(refused);
        }
        if (!due.isEmpty()) {
            log.info("Outbox drain handed off {} of {} due row(s)", handedOff, due.size());
        }
        return handedOff;
    }
}
