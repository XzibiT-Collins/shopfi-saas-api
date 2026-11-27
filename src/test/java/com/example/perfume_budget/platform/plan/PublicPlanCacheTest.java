package com.example.perfume_budget.platform.plan;

import com.example.perfume_budget.platform.plan.dto.PublicPlansView;
import com.example.perfume_budget.platform.plan.service.PlanService;
import com.example.perfume_budget.platform.plan.service.PublicPlanCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** PLAN-01 (plans.md 12, 15.1 item 5): the public list cache: 60 s TTL, evicted only AFTER a commit, never on rollback. */
class PublicPlanCacheTest {

    PlanService service = mock(PlanService.class);
    Instant now = Instant.parse("2026-11-27T12:00:00Z");
    MutableClock clock = new MutableClock();
    PublicPlanCache cache = new PublicPlanCache(service, clock);

    class MutableClock extends Clock {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }

    @AfterEach
    void clearSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void servesFromCacheForSixtySeconds_thenReloads() {
        when(service.publicPlans()).thenReturn(new PublicPlansView(List.of(), List.of()));
        cache.get();
        now = now.plus(Duration.ofSeconds(59));
        cache.get();
        verify(service, times(1)).publicPlans();
        now = now.plus(Duration.ofSeconds(1));
        cache.get();
        verify(service, times(2)).publicPlans();
    }

    @Test
    void evictsAfterCommit_notBefore_andNotOnRollback() {
        when(service.publicPlans()).thenReturn(new PublicPlansView(List.of(), List.of()));
        cache.get();
        // rollback: nothing evicted
        TransactionSynchronizationManager.initSynchronization();
        cache.evictAfterCommit();
        cache.get(); // still inside the transaction: not evicted yet
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        TransactionSynchronizationManager.clearSynchronization();
        cache.get();
        verify(service, times(1)).publicPlans();
        // commit: evicted, the next read reloads
        TransactionSynchronizationManager.initSynchronization();
        cache.evictAfterCommit();
        cache.get();
        verify(service, times(1)).publicPlans();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        TransactionSynchronizationManager.clearSynchronization();
        cache.get();
        verify(service, times(2)).publicPlans();
    }
}
