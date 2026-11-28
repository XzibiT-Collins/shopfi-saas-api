package com.example.perfume_budget.platform.domain;

import com.example.perfume_budget.entitlement.NotPlanGated;
import com.example.perfume_budget.utils.SafeStackTrace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * DOMAIN-03: drives {@link CustomDomainReverifier#runRound()} on a thread of its own (DNS lookups can block for
 * seconds; the shared {@code @Scheduled} thread also runs the STOMP revalidator). Does nothing unless
 * {@code tenant.custom-domain.reverify.enabled=true}. A failing round is logged (class only) and the next one runs.
 */
@Slf4j
@NotPlanGated(reason = "custom-domain re-verification job: platform system actor, never plan-gated (PO 2026-11-28)")
@Component
public class CustomDomainReverifyScheduler implements SmartLifecycle {

    static final String THREAD_NAME = "custom-domain-reverify";

    private final CustomDomainReverifier reverifier;
    private final CustomDomainReverifyProperties props;
    private volatile ScheduledExecutorService executor;

    public CustomDomainReverifyScheduler(CustomDomainReverifier reverifier, CustomDomainReverifyProperties props) {
        this.reverifier = reverifier;
        this.props = props;
    }

    @Override
    public synchronized void start() {
        if (!props.isEnabled() || executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, THREAD_NAME);
            t.setDaemon(true);
            return t;
        });
        long tick = Math.max(1000, props.getTickInterval().toMillis());
        long initial = Math.max(0, props.getInitialDelay().toMillis());
        executor.scheduleWithFixedDelay(this::round, initial, tick, TimeUnit.MILLISECONDS);
        log.info("Custom domain re-verification scheduled: tick {} ms, batch {}", tick, props.getBatchSize());
    }

    void round() {
        try {
            reverifier.runRound();
        } catch (RuntimeException | Error e) {
            log.error("Custom domain re-verification round failed; the next one runs as scheduled: {}",
                    SafeStackTrace.of(e));
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
            running.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }
}
