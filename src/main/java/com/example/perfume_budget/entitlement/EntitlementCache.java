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
 * BILL-01c generation guard (closes the former "60 s write-back window"): every key has a generation counter; a loader
 * reads it BEFORE the database and writes its value only while it is unchanged (atomic compare-and-set in the store),
 * and every eviction increments it. A request that read the database before a committed change therefore can never
 * write the old value back after the eviction. Eviction of an access change is retried with backoff; if it still fails
 * a metric and an ERROR fire and the key gets a very short-lived marker (a read of the marker is a miss).
 */
@Slf4j
@Component
public class EntitlementCache {

    public static final Duration TTL = Duration.ofSeconds(60);
    public static final String ERRORS_METRIC = "shopfi.entitlement.cache.errors";
    /** Counts evictions that failed after every retry (alert on any increase: access may be stale for up to the TTL). */
    public static final String EVICTION_FAILED_METRIC = "shopfi.entitlement.cache.eviction.failed";
    /** Written over an entry whose eviction failed; read as a miss, expires almost at once. */
    static final String MARKER = "!evicted";
    static final Duration MARKER_TTL = Duration.ofSeconds(2);
    /** Pauses between eviction attempts: 4 attempts in all, at most ~0.5 s on the request that committed the change. */
    static final List<Duration> DEFAULT_BACKOFF = List.of(Duration.ofMillis(25), Duration.ofMillis(100), Duration.ofMillis(400));
    static final String PREFIX = "plans:v1:";
    private static final TypeReference<List<EntitlementView>> ENTITLEMENTS = new TypeReference<>() {
    };

    private final EntitlementStore store;
    private final ObjectProvider<MeterRegistry> meters;
    private final List<Duration> backoff;
    private final ObjectMapper json = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @org.springframework.beans.factory.annotation.Autowired
    public EntitlementCache(EntitlementStore store, ObjectProvider<MeterRegistry> meters) {
        this(store, meters, DEFAULT_BACKOFF);
    }

    EntitlementCache(EntitlementStore store, ObjectProvider<MeterRegistry> meters, List<Duration> backoff) {
        this.store = store;
        this.meters = meters;
        this.backoff = backoff;
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
        long generation = generation(key); // BEFORE the database read (BILL-01c)
        TenantPlanState hit = read(key, TenantPlanState.class, null);
        if (hit != null && hit.tenantId() == tenantId) {
            return Optional.of(hit);
        }
        Optional<TenantPlanState> fresh = loader.get();
        fresh.ifPresent(v -> write(key, v, generation));
        return fresh;
    }

    /** One plan's entitlements in catalog order; {@code loader} reads the master tables. */
    public List<EntitlementView> plan(long planId, Supplier<List<EntitlementView>> loader) {
        String key = planKey(planId);
        long generation = generation(key); // BEFORE the database read (BILL-01c)
        List<EntitlementView> hit = read(key, null, ENTITLEMENTS);
        if (hit != null) {
            return hit;
        }
        List<EntitlementView> fresh = loader.get();
        write(key, fresh, generation);
        return fresh;
    }

    @NotPlanGated(reason = "cache eviction after commit; touches no database and no gate")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPlansChanged(PlansChangedEvent event) {
        evict(event.planIds().stream().map(EntitlementCache::planKey).toList());
    }

    @NotPlanGated(reason = "cache eviction after commit; touches no database and no gate")
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

    /**
     * Increments the generation and deletes each key, atomically per call. A failure is retried with backoff (every change
     * that grants OR revokes access evicts here; a revocation must not wait for the TTL). When every attempt failed: a
     * metric and an ERROR fire and each key gets a short-lived marker so a stale entry cannot outlive a couple of seconds.
     */
    private void evict(List<String> keys) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= backoff.size(); attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(backoff.get(attempt - 1).toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                store.evict(keys);
                return;
            } catch (RuntimeException e) {
                last = e;
                count("evict");
            }
        }
        failedEviction(keys, last);
    }

    private void failedEviction(List<String> keys, RuntimeException cause) {
        countEvictionFailure();
        // loud: a missed eviction keeps the old state for up to the TTL unless the marker below lands
        log.error("Entitlement cache eviction FAILED after {} attempts ({}); entries expire within {} s",
                backoff.size() + 1, cause == null ? "interrupted" : cause.getClass().getSimpleName(), TTL.toSeconds());
        for (String key : keys) {
            try {
                store.put(key, MARKER, MARKER_TTL);
            } catch (RuntimeException e) {
                count("marker");
            }
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
        if (raw == null || MARKER.equals(raw)) {
            return null; // a miss; the marker only exists to push a stale entry out within seconds
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

    /** The key's generation, or -1 when the store failed (then nothing is written back; the database answer is used). */
    private long generation(String key) {
        try {
            return store.generation(key);
        } catch (RuntimeException e) {
            count("get");
            log.warn("Entitlement cache generation read failed ({}); reading the database", e.getClass().getSimpleName());
            return -1;
        }
    }

    /** Writes the loader's value only while the generation read before the database read is unchanged. */
    private void write(String key, Object value, long generation) {
        if (generation < 0) {
            return;
        }
        try {
            if (!store.putIfGeneration(key, json.writeValueAsString(value), TTL, generation)) {
                count("write-rejected"); // a reader lost the race with an eviction: expected, informational
            }
        } catch (Exception e) {
            count("put");
            log.warn("Entitlement cache write failed ({})", e.getClass().getSimpleName());
        }
    }

    private void countEvictionFailure() {
        try {
            MeterRegistry r = meters.getIfAvailable();
            if (r != null) {
                r.counter(EVICTION_FAILED_METRIC).increment();
            }
        } catch (RuntimeException ignored) {
            // metrics never turn a cache problem into a failure
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
