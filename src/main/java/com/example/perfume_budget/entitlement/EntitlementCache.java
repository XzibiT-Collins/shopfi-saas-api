package com.example.perfume_budget.entitlement;

import com.example.perfume_budget.platform.plan.dto.EntitlementView;
import com.example.perfume_budget.platform.plan.events.PlansChangedEvent;
import com.example.perfume_budget.platform.plan.events.TenantSubscriptionChangedEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * PLAN-02 entitlement cache, exactly as docs/api/plans.md 15.1:
 * <ol>
 *   <li>a dedicated store ({@link EntitlementStore}), never the tenant-prefixing {@code TenantAwareCacheManager};</li>
 *   <li>fixed master keys by immutable ids: {@code plans:v1:entitlements:{planId}} and {@code plans:v1:tenant:{tenantId}}
 *       (never a slug);</li>
 *   <li>JSON values (a private mapper, no default typing);</li>
 *   <li>TTL 60 s as a backstop;</li>
 *   <li>eviction AFTER COMMIT on {@link PlansChangedEvent} and {@link TenantSubscriptionChangedEvent};</li>
 *   <li>any store error (or an unreadable value) reads the database: the database answer is used, never a default
 *       grant;</li>
 *   <li>{@code readOnly} is never cached ({@link TenantPlanState} has no such field).</li>
 * </ol>
 * Residual (documented in the PR): a request that read the database just before a committed change may write the old
 * value back after the eviction; the TTL bounds that to 60 s.
 */
@Slf4j
@Component
public class EntitlementCache {

    public static final Duration TTL = Duration.ofSeconds(60);
    public static final String ERRORS_METRIC = "shopfi.entitlement.cache.errors";
    static final String PREFIX = "plans:v1:";
    private static final TypeReference<List<EntitlementView>> ENTITLEMENTS = new TypeReference<>() {
    };

    private final EntitlementStore store;
    private final ObjectProvider<MeterRegistry> meters;
    private final ObjectMapper json = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public EntitlementCache(EntitlementStore store, ObjectProvider<MeterRegistry> meters) {
        this.store = store;
        this.meters = meters;
    }

    public static String tenantKey(long tenantId) {
        return PREFIX + "tenant:" + tenantId;
    }

    public static String planKey(long planId) {
        return PREFIX + "entitlements:" + planId;
    }

    /** The tenant's plan id / status / trial end; {@code loader} reads the master table (empty: no such tenant). */
    public Optional<TenantPlanState> tenant(long tenantId, Supplier<Optional<TenantPlanState>> loader) {
        String key = tenantKey(tenantId);
        TenantPlanState hit = read(key, TenantPlanState.class, null);
        if (hit != null && hit.tenantId() == tenantId) {
            return Optional.of(hit);
        }
        Optional<TenantPlanState> fresh = loader.get();
        fresh.ifPresent(v -> write(key, v));
        return fresh;
    }

    /** One plan's entitlements in catalog order; {@code loader} reads the master tables. */
    public List<EntitlementView> plan(long planId, Supplier<List<EntitlementView>> loader) {
        String key = planKey(planId);
        List<EntitlementView> hit = read(key, null, ENTITLEMENTS);
        if (hit != null) {
            return hit;
        }
        List<EntitlementView> fresh = loader.get();
        write(key, fresh);
        return fresh;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPlansChanged(PlansChangedEvent event) {
        evict(event.planIds().stream().map(EntitlementCache::planKey).toList());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTenantSubscriptionChanged(TenantSubscriptionChangedEvent event) {
        if (event.tenantId() != null) {
            evict(List.of(tenantKey(event.tenantId())));
        }
    }

    /** Evicts at once (callers that run outside a transaction, e.g. provisioning after its master commit). */
    public void evictTenant(long tenantId) {
        evict(List.of(tenantKey(tenantId)));
    }

    private void evict(List<String> keys) {
        try {
            store.delete(keys);
        } catch (RuntimeException e) {
            count("evict");
            // loud: a missed eviction keeps the old state for up to the TTL
            log.error("Entitlement cache eviction failed ({}); entries expire within {} s", e.getClass().getSimpleName(),
                    TTL.toSeconds());
        }
    }

    private <T> T read(String key, Class<T> type, TypeReference<T> ref) {
        String raw;
        try {
            raw = store.get(key);
        } catch (RuntimeException e) {
            count("get");
            log.warn("Entitlement cache read failed ({}); reading the database", e.getClass().getSimpleName());
            return null;
        }
        if (raw == null) {
            return null;
        }
        try {
            return type != null ? json.readValue(raw, type) : json.readValue(raw, ref);
        } catch (Exception e) {
            count("get");
            log.warn("Entitlement cache entry unreadable ({}); reading the database", e.getClass().getSimpleName());
            evict(List.of(key));
            return null;
        }
    }

    private void write(String key, Object value) {
        try {
            store.put(key, json.writeValueAsString(value), TTL);
        } catch (Exception e) {
            count("put");
            log.warn("Entitlement cache write failed ({})", e.getClass().getSimpleName());
        }
    }

    private void count(String op) {
        try {
            MeterRegistry r = meters.getIfAvailable();
            if (r != null) {
                r.counter(ERRORS_METRIC, "op", op).increment();
            }
        } catch (RuntimeException ignored) {
            // metrics never turn a cache problem into a failure
        }
    }
}
