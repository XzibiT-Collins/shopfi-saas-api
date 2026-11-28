package com.example.perfume_budget.entitlement;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.perfume_budget.platform.enums.SubscriptionStatus;
import com.example.perfume_budget.platform.plan.dto.EntitlementView;
import com.example.perfume_budget.platform.plan.events.PlansChangedEvent;
import com.example.perfume_budget.platform.plan.events.TenantSubscriptionChangedEvent;
import com.example.perfume_budget.support.InMemoryEntitlementStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BILL-01c AC1, AC2, AC8 on the cache with deterministic interleavings (no sleeps): the per-key generation guard (a reader
 * that read the database before a committed change can never write the old value back after the eviction), retried
 * eviction with a metric, an ERROR log and a short-lived marker, and the 200-round reader/writer race.
 */
class EntitlementCacheGenerationGuardTest {

    private final InMemoryEntitlementStore store = new InMemoryEntitlementStore();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final EntitlementCache cache = new EntitlementCache(store, provider(meters), List.of(Duration.ZERO, Duration.ZERO, Duration.ZERO));
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger cacheLogger = (Logger) LoggerFactory.getLogger(EntitlementCache.class);

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> provider(MeterRegistry r) {
        ObjectProvider<MeterRegistry> p = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(p.getIfAvailable()).thenReturn(r);
        return p;
    }

    @BeforeEach
    void on() {
        store.mode = InMemoryEntitlementStore.Mode.ON;
        logs.start();
        cacheLogger.addAppender(logs);
    }

    @AfterEach
    void off() {
        cacheLogger.detachAppender(logs);
        store.reset();
    }

    private static TenantPlanState state(long tenant, long plan) {
        return new TenantPlanState(tenant, plan, SubscriptionStatus.ACTIVE, null);
    }

    private static List<EntitlementView> ents(boolean enabled) {
        return List.of(new EntitlementView("WALK_IN_POS", enabled, null));
    }

    // ------------------------------------------------------------------------------------------ AC1

    @Test
    void tenantKey_aLateWriteOfTheOldStateIsRejectedAfterAnEviction_andTheNextReadSeesTheNewState() {
        // the reader read the database (plan 1) BEFORE a change committed; the change commits and evicts while the reader
        // is still on its way to the cache; only then does the reader try to write its (old) value
        Optional<TenantPlanState> reader = cache.tenant(7, () -> {
            cache.onTenantSubscriptionChanged(new TenantSubscriptionChangedEvent(7L)); // commit + eviction in between
            return Optional.of(state(7, 1)); // the stale database read
        });
        assertThat(reader.orElseThrow().planId()).as("the racing request itself may answer with what it read").isEqualTo(1);
        assertThat(store.values).as("the late write-back was REJECTED").doesNotContainKey("plans:v1:tenant:7");

        AtomicInteger loads = new AtomicInteger();
        assertThat(cache.tenant(7, () -> {
            loads.incrementAndGet();
            return Optional.of(state(7, 2));
        }).orElseThrow().planId()).as("the next read goes to the database and sees the new state").isEqualTo(2);
        assertThat(loads).hasValue(1);
        assertThat(store.values.get("plans:v1:tenant:7")).contains("\"planId\":2");
    }

    @Test
    void planKey_aLateWriteOfTheOldEntitlementsIsRejectedAfterAnEviction() {
        List<EntitlementView> reader = cache.plan(2, () -> {
            cache.onPlansChanged(new PlansChangedEvent(Set.of(2L)));
            return ents(true); // old: the feature was still on
        });
        assertThat(reader).isEqualTo(ents(true));
        assertThat(store.values).doesNotContainKey("plans:v1:entitlements:2");
        assertThat(cache.plan(2, () -> ents(false))).as("revoked feature is never served from the old write").isEqualTo(ents(false));
        assertThat(store.values.get("plans:v1:entitlements:2")).contains("\"enabled\":false");
    }

    @Test
    void anEvictionOfAnotherKeyDoesNotRejectTheWrite() {
        cache.tenant(7, () -> {
            cache.onTenantSubscriptionChanged(new TenantSubscriptionChangedEvent(8L));
            return Optional.of(state(7, 1));
        });
        assertThat(store.values).containsKey("plans:v1:tenant:7");
    }

    @Test
    void withThreads_theReaderIsHeldAtTheDatabaseRead_theWriterCommitsAndEvicts_thenTheReaderWrites() throws Exception {
        CountDownLatch readerHasOldState = new CountDownLatch(1);
        CountDownLatch writerDone = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<TenantPlanState>> reader = pool.submit(() -> cache.tenant(5, () -> {
                readerHasOldState.countDown();
                await(writerDone);
                return Optional.of(state(5, 1));
            }));
            await(readerHasOldState);
            pool.submit(() -> {
                cache.onTenantSubscriptionChanged(new TenantSubscriptionChangedEvent(5L));
                writerDone.countDown();
            }).get(5, TimeUnit.SECONDS);
            assertThat(reader.get(5, TimeUnit.SECONDS).orElseThrow().planId()).isEqualTo(1);
            assertThat(store.values).doesNotContainKey("plans:v1:tenant:5");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void generationIsReadBeforeTheDatabase_aStoreFailureOnTheGenerationNeverWritesBack() {
        store.afterGenerationRead = () -> store.mode = InMemoryEntitlementStore.Mode.DOWN;
        assertThat(cache.tenant(3, () -> Optional.of(state(3, 1))).orElseThrow().planId()).isEqualTo(1);
        store.mode = InMemoryEntitlementStore.Mode.ON;
        assertThat(store.values).isEmpty();
    }

    @Test
    void theGenerationOnlyEverGrows_andEveryEvictionBumpsIt() {
        assertThat(store.generation("plans:v1:tenant:9")).isZero();
        cache.evictTenant(9);
        cache.evictTenant(9);
        assertThat(store.generation("plans:v1:tenant:9")).isEqualTo(2);
        assertThat(EntitlementStore.generationKey("plans:v1:tenant:9")).isEqualTo("plans:v1:gen:tenant:9");
        assertThat(EntitlementStore.generationKey("plans:v1:entitlements:2")).isEqualTo("plans:v1:gen:entitlements:2");
    }

    // ------------------------------------------------------------------------------------------ AC8 race x 200

    /** Two readers and one writer, 200 rounds behind a barrier; after the writer's eviction nobody can see the old state. */
    @Test
    void twoReadersOneWriter_200RoundsWithABarrier_theOldValueNeverSurvivesTheEviction() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < 200; round++) {
                store.values.clear();
                store.generations.clear();
                long tenant = 100 + round;
                AtomicInteger db = new AtomicInteger(1); // the "database": the plan id of the tenant
                CyclicBarrier barrier = new CyclicBarrier(3);
                Runnable reader = () -> {
                    await(barrier);
                    cache.tenant(tenant, () -> Optional.of(state(tenant, db.get())));
                };
                Future<?> r1 = pool.submit(reader);
                Future<?> r2 = pool.submit(reader);
                Future<?> w = pool.submit(() -> {
                    await(barrier);
                    db.set(2); // the change commits ...
                    cache.onTenantSubscriptionChanged(new TenantSubscriptionChangedEvent(tenant)); // ... and evicts
                });
                r1.get(10, TimeUnit.SECONDS);
                r2.get(10, TimeUnit.SECONDS);
                w.get(10, TimeUnit.SECONDS);
                String cached = store.values.get("plans:v1:tenant:" + tenant);
                assertThat(cached == null || cached.contains("\"planId\":2"))
                        .as("round %d: cached %s", round, cached).isTrue();
                assertThat(cache.tenant(tenant, () -> Optional.of(state(tenant, db.get()))).orElseThrow().planId()).isEqualTo(2);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void tenantAIsNeverServedToTenantB_underConcurrentLoads() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Boolean>> all = new java.util.ArrayList<>();
            for (int i = 0; i < 200; i++) {
                long t = 1 + i % 2;
                all.add(pool.submit(() -> cache.tenant(t, () -> Optional.of(state(t, 10 + t))).orElseThrow().tenantId() == t));
            }
            for (Future<Boolean> f : all) {
                assertThat(f.get(10, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(store.values.get("plans:v1:tenant:1")).contains("\"tenantId\":1").contains("\"planId\":11");
            assertThat(store.values.get("plans:v1:tenant:2")).contains("\"tenantId\":2").contains("\"planId\":12");
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------------------------------ AC2

    @Test
    void aFailedEvictionIsRetried_andSucceedsOnTheThirdAttempt() {
        cache.tenant(4, () -> Optional.of(state(4, 1)));
        store.failEvicts.set(2);
        cache.onTenantSubscriptionChanged(new TenantSubscriptionChangedEvent(4L));
        assertThat(store.evictAttempts).as("two failures, then the retry that works").hasValue(3);
        assertThat(store.values).doesNotContainKey("plans:v1:tenant:4");
        assertThat(meters.find(EntitlementCache.EVICTION_FAILED_METRIC).counter()).as("no final failure").isNull();
        assertThat(logs.list).noneMatch(e -> e.getLevel() == Level.ERROR);
        assertThat(cache.tenant(4, () -> Optional.of(state(4, 2))).orElseThrow().planId()).isEqualTo(2);
    }

    @Test
    void anEvictionThatNeverSucceeds_isBounded_firesTheMetricAndAnErrorLog_andLeavesAShortLivedMarker() {
        cache.tenant(4, () -> Optional.of(state(4, 1)));
        store.failEvicts.set(Integer.MAX_VALUE);
        cache.onTenantSubscriptionChanged(new TenantSubscriptionChangedEvent(4L)); // never throws into the writer
        assertThat(store.evictAttempts).as("bounded: 1 + 3 retries").hasValue(4);
        assertThat(meters.find(EntitlementCache.EVICTION_FAILED_METRIC).counter().count()).isEqualTo(1.0);
        assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.ERROR && e.getFormattedMessage().contains("eviction FAILED"));
        assertThat(store.values.get("plans:v1:tenant:4")).as("the stale entry was overwritten by the marker")
                .isEqualTo(EntitlementCache.MARKER);
        assertThat(store.ttls.get("plans:v1:tenant:4")).isEqualTo(Duration.ofSeconds(2));
        assertThat(store.generation("plans:v1:tenant:4")).as("the marker also bumped the generation (QA L1)").isEqualTo(1);
        // a read of the marker is a miss: the database answers, and its value replaces the marker
        assertThat(cache.tenant(4, () -> Optional.of(state(4, 2))).orElseThrow().planId()).isEqualTo(2);
        assertThat(store.values.get("plans:v1:tenant:4")).contains("\"planId\":2");
        assertThat(meters.find(EntitlementCache.ERRORS_METRIC).tag("op", "get").counter())
                .as("the marker is a plain miss, not an unreadable entry").isNull();
    }

    @Test
    void aFailedPlanEvictionIsRetriedTheSameWay() {
        cache.plan(2, () -> ents(true));
        store.failEvicts.set(1);
        cache.onPlansChanged(new PlansChangedEvent(Set.of(2L)));
        assertThat(store.evictAttempts).hasValue(2);
        assertThat(store.values).doesNotContainKey("plans:v1:entitlements:2");
    }

    @Test
    void redisDown_readsTheDatabaseEveryTime_neverGrantsByDefault_andNeverThrowsFromAnEviction() {
        store.mode = InMemoryEntitlementStore.Mode.DOWN;
        assertThat(cache.plan(2, () -> ents(false))).isEqualTo(ents(false));
        assertThat(cache.tenant(1, Optional::empty)).isEmpty();
        cache.onPlansChanged(new PlansChangedEvent(Set.of(2L)));
        assertThat(meters.find(EntitlementCache.EVICTION_FAILED_METRIC).counter().count()).isEqualTo(1.0);
    }

    // ------------------------------------------------------------------------------------------ helpers

    private static void await(CountDownLatch l) {
        try {
            assertThat(l.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void await(CyclicBarrier b) {
        try {
            b.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }


    /** QA L1: a reader that read the old state before the change, with the eviction failing, cannot write it back after the marker. */
    @Test
    void afterAFailedEvictionTheMarkerBumpsTheGeneration_soALateReaderCannotWriteTheOldStateBack() {
        store.failEvicts.set(Integer.MAX_VALUE);
        Optional<TenantPlanState> reader = cache.tenant(6, () -> {
            cache.onTenantSubscriptionChanged(new TenantSubscriptionChangedEvent(6L)); // eviction fails, marker lands
            return Optional.of(state(6, 1)); // the stale read
        });
        assertThat(reader.orElseThrow().planId()).isEqualTo(1);
        assertThat(store.values.get("plans:v1:tenant:6")).as("still the marker, the old state was NOT written back")
                .isEqualTo(EntitlementCache.MARKER);
    }

    @Test
    void evictionStopsWithinItsWallTimeBudget_evenWhenEveryStoreCallIsSlow() {
        EntitlementCache slow = new EntitlementCache(new InMemoryEntitlementStore() {
            @Override public synchronized void evict(java.util.Collection<String> keys) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new org.springframework.data.redis.RedisConnectionFailureException("slow (test)");
            }
        }, provider(meters), List.of(Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofMillis(100),
                Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofMillis(100)), Duration.ofMillis(900));
        long t0 = System.nanoTime();
        slow.evictTenant(1);
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertThat(ms).as("9 attempts x 300 ms + pauses would be 3.5 s; the budget stops it").isLessThan(1500);
        assertThat(meters.find(EntitlementCache.EVICTION_FAILED_METRIC).counter().count()).isEqualTo(1.0);
    }
}
