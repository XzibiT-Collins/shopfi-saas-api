package com.example.perfume_budget.outbox;

import com.example.perfume_budget.entitlement.NotPlanGated;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.example.perfume_budget.utils.SafeStackTrace;

/**
 * EVT-01c (7): drives {@link OutboxDrainer#drainAllTenants()} on a thread of its own ({@value #THREAD_PREFIX}N), with
 * a fixed delay between rounds. The application's {@code @Scheduled} jobs share Spring's single scheduling thread; a
 * drain round over many tenants there would delay them all (and they it). Deliberately not a {@code TaskScheduler}
 * bean: one would replace the scheduler every {@code @Scheduled} job uses.
 *
 * <p>A failing round is logged (class only) and the next one still runs (a plain {@code scheduleWithFixedDelay}
 * stops for good after an exception). Stopped (in-flight round interrupted, at most a few seconds) on shutdown,
 * before the mail executors it hands rows to.
 */
@Slf4j
@NotPlanGated(reason = "outbox delivery (mail, notifications) of work that already happened, never plan-gated")
@Component
public class OutboxDrainScheduler implements SmartLifecycle {

    static final String THREAD_PREFIX = "outbox-drain-";

    private final OutboxDrainer drainer;
    private final long intervalMs;
    private final long initialDelayMs;
    private final AtomicInteger threads = new AtomicInteger();
    private volatile ScheduledExecutorService executor;

    public OutboxDrainScheduler(OutboxDrainer drainer,
                                @Value("${shopfi.outbox.drain-interval-ms:30000}") long intervalMs,
                                @Value("${shopfi.outbox.drain-initial-delay-ms:60000}") long initialDelayMs) {
        this.drainer = drainer;
        this.intervalMs = Math.max(1, intervalMs);
        this.initialDelayMs = Math.max(0, initialDelayMs);
    }

    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, THREAD_PREFIX + threads.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::round, initialDelayMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    void round() {
        try {
            drainer.drainAllTenants();
        } catch (RuntimeException | Error e) {
            log.error("Outbox drain round failed; the next round runs as scheduled: {}", SafeStackTrace.of(e));
        }
    }

    @Override
    public synchronized void stop() {
        ScheduledExecutorService running = executor;
        executor = null;
        if (running == null) {
            return;
        }
        running.shutdownNow();
        try {
            if (!running.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Outbox drain round still running at shutdown; left behind");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }
}
