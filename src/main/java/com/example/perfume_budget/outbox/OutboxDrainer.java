package com.example.perfume_budget.outbox;

import com.example.perfume_budget.handlers.MailDispatcher;
import com.example.perfume_budget.tenancy.TenantRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * EVT-01b (L2): re-drives the side-effect outbox of every active tenant: rows whose immediate hand-off was refused
 * (executor full or shut down), whose worker died, or whose send failed and is due for a retry. Rows are handed to
 * the same bounded executors without waiting; when an executor is full the rest of the batch is released and tried
 * on the next run. DONE rows are purged after {@link #RETENTION}; FAILED rows stay for an operator.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxDrainer {

    static final int BATCH = 100;
    static final Duration RETENTION = Duration.ofDays(14);

    private final OutboxStore store;
    private final OutboxProcessor processor;
    private final MailDispatcher mailDispatcher;
    private final TenantRunner tenantRunner;

    @Scheduled(fixedDelayString = "${shopfi.outbox.drain-interval-ms:30000}",
            initialDelayString = "${shopfi.outbox.drain-initial-delay-ms:60000}")
    public void drainAllTenants() {
        tenantRunner.forEachActiveTenant(this::drainCurrentTenant);
    }

    /** One drain of the current tenant's outbox. @return how many rows were handed off */
    public int drainCurrentTenant() {
        store.purgeDone(RETENTION);
        List<OutboxStore.Row> due = store.reserveDue(BATCH);
        int handedOff = 0;
        for (OutboxStore.Row row : due) {
            if (!mailDispatcher.handOff(row.kind(), row.describe(), () -> processor.runReserved(row))) {
                List<Long> rest = new ArrayList<>();
                due.subList(handedOff, due.size()).forEach(r -> rest.add(r.id()));
                store.release(rest);
                break;
            }
            handedOff++;
        }
        if (!due.isEmpty()) {
            log.info("Outbox drain handed off {} of {} due row(s)", handedOff, due.size());
        }
        return handedOff;
    }
}
