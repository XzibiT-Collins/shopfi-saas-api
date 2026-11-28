package com.example.perfume_budget.entitlement;

import com.example.perfume_budget.platform.enums.SubscriptionStatus;
import com.example.perfume_budget.platform.plan.dto.EntitlementView;
import com.example.perfume_budget.platform.plan.events.PlansChangedEvent;
import com.example.perfume_budget.platform.plan.events.TenantSubscriptionChangedEvent;
import com.example.perfume_budget.support.InMemoryEntitlementStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** plans.md 15.1 items 2-7 on the cache itself (no Spring): keys, JSON, TTL, eviction, errors -> database. */
class EntitlementCacheTest {

    @SuppressWarnings("unchecked")
    private final InMemoryEntitlementStore store = new InMemoryEntitlementStore();
    private final EntitlementCache cache = new EntitlementCache(store, mock(ObjectProvider.class));
    private final AtomicInteger loads = new AtomicInteger();

    private Optional<TenantPlanState> load(long id, long plan) {
        loads.incrementAndGet();
        return Optional.of(new TenantPlanState(id, plan, SubscriptionStatus.TRIALING, Instant.parse("2030-01-01T00:00:00Z")));
    }

    @Test
    void fixedMasterKeysByImmutableIds_jsonValues_ttlAtMost60s() {
        store.mode = InMemoryEntitlementStore.Mode.ON;
        cache.tenant(7, () -> load(7, 2));
        cache.plan(2, () -> List.of(new EntitlementView("WALK_IN_POS", true, null)));
        assertThat(store.values.keySet()).containsExactlyInAnyOrder("plans:v1:tenant:7", "plans:v1:entitlements:2");
        assertThat(store.values.get("plans:v1:tenant:7"))
                .isEqualTo("{\"tenantId\":7,\"planId\":2,\"status\":\"TRIALING\",\"trialEndsAt\":\"2030-01-01T00:00:00Z\",\"accessState\":\"FULL\",\"billingManaged\":false}");
        assertThat(store.values.get("plans:v1:entitlements:2")).isEqualTo("[{\"featureKey\":\"WALK_IN_POS\",\"enabled\":true,\"limitValue\":null}]");
        assertThat(store.ttls.values()).allSatisfy(ttl -> assertThat(ttl).isLessThanOrEqualTo(Duration.ofSeconds(60)).isPositive());
        assertThat(store.values.values()).noneMatch(v -> v.contains("@class") || v.contains("java."));
    }

    @Test
    void aHitIsServedWithoutTheDatabase_andTenantAIsNeverServedForTenantB() {
        store.mode = InMemoryEntitlementStore.Mode.ON;
        assertThat(cache.tenant(1, () -> load(1, 1)).orElseThrow().planId()).isEqualTo(1);
        assertThat(cache.tenant(1, () -> load(1, 3)).orElseThrow().planId()).as("hit").isEqualTo(1);
        assertThat(loads).hasValue(1);
        assertThat(cache.tenant(2, () -> load(2, 3)).orElseThrow().planId()).as("B has its own entry").isEqualTo(3);
        // an entry stored under B's key that names another tenant is never served for B
        store.values.put("plans:v1:tenant:5", store.values.get("plans:v1:tenant:1"));
        assertThat(cache.tenant(5, () -> load(5, 2)).orElseThrow()).extracting(TenantPlanState::tenantId, TenantPlanState::planId)
                .containsExactly(5L, 2L);
    }

    @Test
    void storeDown_readsTheDatabaseEveryTime_neverADefault() {
        store.mode = InMemoryEntitlementStore.Mode.DOWN;
        assertThat(cache.tenant(1, () -> load(1, 1)).orElseThrow().planId()).isEqualTo(1);
        assertThat(cache.plan(1, () -> List.of(new EntitlementView("WALK_IN_POS", false, null))))
                .containsExactly(new EntitlementView("WALK_IN_POS", false, null));
        assertThat(cache.tenant(1, () -> Optional.empty())).as("unknown tenant stays unknown").isEmpty();
        cache.onPlansChanged(new PlansChangedEvent(Set.of(1L))); // a failing eviction never throws into the writer
    }

    @Test
    void anUnreadableEntryIsAMiss_andIsDropped() {
        store.mode = InMemoryEntitlementStore.Mode.ON;
        store.values.put("plans:v1:entitlements:4", "¬í\u0000\u0005sr"); // e.g. a JDK-serialised value
        assertThat(cache.plan(4, () -> List.of(new EntitlementView("ACCOUNTING", false, null)))).hasSize(1);
        assertThat(store.values.get("plans:v1:entitlements:4")).startsWith("[");
    }

    @Test
    void evictsOnBothEvents_afterCommitOnly() throws Exception {
        store.mode = InMemoryEntitlementStore.Mode.ON;
        cache.tenant(3, () -> load(3, 2));
        cache.plan(2, List::of);
        cache.plan(3, List::of);
        cache.onPlansChanged(new PlansChangedEvent(Set.of(2L)));
        assertThat(store.values.keySet()).containsExactlyInAnyOrder("plans:v1:tenant:3", "plans:v1:entitlements:3");
        cache.onTenantSubscriptionChanged(new TenantSubscriptionChangedEvent(3L));
        assertThat(store.values.keySet()).containsExactly("plans:v1:entitlements:3");
        for (String m : List.of("onPlansChanged", "onTenantSubscriptionChanged")) {
            var method = java.util.Arrays.stream(EntitlementCache.class.getMethods()).filter(x -> x.getName().equals(m)).findFirst().orElseThrow();
            TransactionalEventListener l = AnnotatedElementUtils.findMergedAnnotation(method, TransactionalEventListener.class);
            assertThat(l).isNotNull();
            assertThat(l.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        }
    }

    @Test
    void theCacheIsNotTheTenantAwareCacheManager() {
        assertThat(java.util.Arrays.stream(EntitlementCache.class.getDeclaredFields()).map(f -> f.getType().getName()))
                .noneMatch(n -> n.contains("CacheManager"));
        assertThat(java.util.Arrays.stream(RedisEntitlementStore.class.getDeclaredFields()).map(f -> f.getType().getName()).toList())
                .containsExactly(org.springframework.data.redis.core.StringRedisTemplate.class.getName());
    }
}
