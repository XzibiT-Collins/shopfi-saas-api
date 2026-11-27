package com.example.perfume_budget.platform.plan.service;

import com.example.perfume_budget.platform.plan.dto.PublicPlansView;
import com.example.perfume_budget.platform.plan.events.PlansChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Short in-process cache of the public plan list (plans.md section 12: never a master-DB read per hit, at most 60 s
 * stale). It holds only public data (the public shape of public active plans), so a per-instance cache cannot leak
 * anything; it is not tenant data and never goes through {@code TenantAwareCacheManager}. A committed plan write evicts it
 * at once on this instance ({@link PlansChangedEvent}, after commit); other instances converge within the TTL.
 */
@Component
public class PublicPlanCache {

    static final Duration TTL = Duration.ofSeconds(60);

    private record Entry(PublicPlansView value, Instant loadedAt) {
    }

    private final PlanService planService;
    private final Clock clock;
    private final AtomicReference<Entry> entry = new AtomicReference<>();

    public PublicPlanCache(PlanService planService, Clock systemClock) {
        this.planService = planService;
        this.clock = systemClock;
    }

    public PublicPlansView get() {
        Instant now = clock.instant();
        Entry e = entry.get();
        if (e != null && now.isBefore(e.loadedAt().plus(TTL))) {
            return e.value();
        }
        PublicPlansView fresh = planService.publicPlans();
        entry.set(new Entry(fresh, now));
        return fresh;
    }

    /** After commit only: a rolled-back write evicts nothing; a committed one is visible on the next request. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPlansChanged(PlansChangedEvent event) {
        evict();
    }

    public void evict() {
        entry.set(null);
    }
}
