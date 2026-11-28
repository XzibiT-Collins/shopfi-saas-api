package com.example.perfume_budget.platform.plan;

import com.example.perfume_budget.platform.plan.dto.PublicPlansView;
import com.example.perfume_budget.platform.plan.service.PlanService;
import com.example.perfume_budget.platform.plan.service.PublicPlanCache;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BILL-01c (PLAN-01 Security low): single-flight refresh of the public plans cache under load, and the generation guard
 * that keeps a reload which started before an eviction from putting the old list back.
 */
class PublicPlanCacheSingleFlightTest {

    private volatile Instant now = Instant.parse("2026-11-28T12:00:00Z");
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    };
    private final PlanService service = mock(PlanService.class);
    private final PublicPlanCache cache = new PublicPlanCache(service, clock);
    private final AtomicInteger loads = new AtomicInteger();

    private static PublicPlansView view() {
        return new PublicPlansView(List.of(), List.of());
    }

    @Test
    void underExpiry_oneThreadReloads_theOthersServeTheStaleListWithoutWaiting() throws Exception {
        when(service.publicPlans()).thenReturn(view());
        PublicPlansView first = cache.get();
        now = now.plus(Duration.ofSeconds(61)); // expired

        CountDownLatch reloadStarted = new CountDownLatch(1);
        CountDownLatch releaseReload = new CountDownLatch(1);
        PublicPlansView reloaded = view();
        when(service.publicPlans()).thenAnswer(i -> {
            loads.incrementAndGet();
            reloadStarted.countDown();
            assertThat(releaseReload.await(10, TimeUnit.SECONDS)).isTrue();
            return reloaded;
        });
        ExecutorService pool = Executors.newFixedThreadPool(33);
        try {
            Future<PublicPlansView> loader = pool.submit(cache::get);
            assertThat(reloadStarted.await(10, TimeUnit.SECONDS)).isTrue();
            List<Future<PublicPlansView>> others = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                others.add(pool.submit(cache::get));
            }
            for (Future<PublicPlansView> f : others) {
                // returns while the reload is still blocked: it served the stale value, it did not wait or reload
                assertThat(f.get(5, TimeUnit.SECONDS)).isSameAs(first);
            }
            assertThat(loads).as("one reload in flight, 32 callers did not start another").hasValue(1);
            releaseReload.countDown();
            assertThat(loader.get(5, TimeUnit.SECONDS)).isSameAs(reloaded);
            assertThat(cache.get()).isSameAs(reloaded);
            assertThat(loads).hasValue(1);
        } finally {
            releaseReload.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void coldStart_manyThreadsAtOnce_exactlyOneDatabaseLoad_everyoneGetsTheListAfterIt() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        PublicPlansView loaded = view();
        when(service.publicPlans()).thenAnswer(i -> {
            loads.incrementAndGet();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return loaded;
        });
        ExecutorService pool = Executors.newFixedThreadPool(24);
        try {
            List<Future<PublicPlansView>> all = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                all.add(pool.submit(cache::get));
            }
            Thread.sleep(200); // let them all reach the cache; none may have loaded a second time
            assertThat(loads.get()).isLessThanOrEqualTo(1);
            release.countDown();
            for (Future<PublicPlansView> f : all) {
                assertThat(f.get(10, TimeUnit.SECONDS)).isSameAs(loaded);
            }
            assertThat(loads).hasValue(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void afterAnEviction_thereIsNothingStaleToServe_callersWaitForTheOneReload() throws Exception {
        when(service.publicPlans()).thenReturn(view());
        cache.get();
        cache.evict();
        CountDownLatch release = new CountDownLatch(1);
        PublicPlansView fresh = view();
        when(service.publicPlans()).thenAnswer(i -> {
            loads.incrementAndGet();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return fresh;
        });
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<PublicPlansView>> all = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                all.add(pool.submit(cache::get));
            }
            Thread.sleep(200);
            release.countDown();
            for (Future<PublicPlansView> f : all) {
                assertThat(f.get(10, TimeUnit.SECONDS)).isSameAs(fresh); // never the evicted list
            }
            assertThat(loads).hasValue(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void aReloadThatStartedBeforeAnEvictionNeverPutsTheOldListBack() {
        PublicPlansView old = view();
        PublicPlansView changed = view();
        when(service.publicPlans()).thenAnswer(i -> {
            loads.incrementAndGet();
            if (loads.get() == 1) {
                cache.evict(); // a plan write committed and evicted while this load was reading the database
                return old;
            }
            return changed;
        });
        assertThat(cache.get()).as("the racing request answers with what it read").isSameAs(old);
        assertThat(cache.get()).as("but the old list was not stored: the next read reloads").isSameAs(changed);
        assertThat(loads).hasValue(2);
        assertThat(cache.get()).isSameAs(changed);
        assertThat(loads).hasValue(2);
    }
}
