package com.example.perfume_budget.platform.plan.service;

import com.example.perfume_budget.platform.plan.dto.PublicPlansView;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Short in-process cache of the public plan list (plans.md section 12: never a master-DB read per hit, at most 60 s
 * stale). It holds only public data (the public shape of public active plans), so a per-instance cache cannot leak
 * anything; it is not tenant data and never goes through {@code TenantAwareCacheManager}. A committed plan write evicts it
 * at once on this instance ({@link #evictAfterCommit()}); other instances converge within the TTL.
 *
 * <p>BILL-01c: <b>single-flight refresh</b>. When the entry has expired exactly one request reloads it; the others keep
 * serving the expired value for the (short) duration of that reload, or, when there is none (first call, or just after an
 * eviction), wait for the one reload instead of each hitting the master database. <b>Generation guard</b>: every eviction
 * increments a counter and a loader stores its value only if the counter is the one it read before the database read, so a
 * reload that started before a committed plan write can never put the old list back after the eviction.
 */
@Component
public class PublicPlanCache {

    static final Duration TTL = Duration.ofSeconds(60);
    /** How long a caller with nothing to serve waits for the one reload before it is answered 503 TRY_AGAIN. */
    static final Duration COLD_WAIT = Duration.ofSeconds(3);

    private record Entry(PublicPlansView value, Instant loadedAt) {
    }

    private final PlanService planService;
    private final Clock clock;
    private final AtomicReference<Entry> entry = new AtomicReference<>();
    private final AtomicLong generation = new AtomicLong();
    private final ReentrantLock refresh = new ReentrantLock();

    private final Duration coldWait;

    @org.springframework.beans.factory.annotation.Autowired
    public PublicPlanCache(PlanService planService, Clock systemClock) {
        this(planService, systemClock, COLD_WAIT);
    }

    public PublicPlanCache(PlanService planService, Clock systemClock, Duration coldWait) {
        this.planService = planService;
        this.clock = systemClock;
        this.coldWait = coldWait;
    }

    public PublicPlansView get() {
        Entry e = entry.get();
        if (e != null && fresh(e)) {
            return e.value();
        }
        if (e != null) {
            // expired: one reloads, everyone else serves the expired value meanwhile
            if (!refresh.tryLock()) {
                return e.value();
            }
        } else {
            // nothing to serve: wait for the single reload, but never pile up without bound (Security low): after the
            // wait a 503 TRY_AGAIN is answered instead of one more thread waiting on the master database
            boolean got;
            try {
                got = refresh.tryLock(coldWait.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                got = false;
            }
            if (!got) {
                throw new com.example.perfume_budget.exception.ApiCodeException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "TRY_AGAIN", "The plans are being loaded. Please try again.");
            }
        }
        try {
            Entry again = entry.get();
            if (again != null && fresh(again)) {
                return again.value(); // another thread reloaded while this one waited
            }
            long gen = generation.get(); // BEFORE the database read
            Instant loadedAt = clock.instant();
            PublicPlansView loaded = planService.publicPlans();
            synchronized (generation) {
                if (generation.get() == gen) {
                    entry.set(new Entry(loaded, loadedAt));
                }
            }
            return loaded;
        } finally {
            refresh.unlock();
        }
    }

    private boolean fresh(Entry e) {
        return clock.instant().isBefore(e.loadedAt().plus(TTL));
    }

    /**
     * Called by every plan write that changed something. After commit only: a rolled-back write evicts nothing; a committed
     * one is visible on the next request. An explicit after-commit synchronization (an in-memory reset that needs no
     * transaction or connection), not an event listener: the code base allows only AFTER_COMMIT hand-off listeners
     * (ListenerPhaseStructureTest). Outside a transaction it evicts at once.
     */
    public void evictAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict();
                }
            });
        } else {
            evict();
        }
    }

    public void evict() {
        synchronized (generation) { // the loader's check-and-store is atomic with this
            generation.incrementAndGet();
            entry.set(null);
        }
    }
}
