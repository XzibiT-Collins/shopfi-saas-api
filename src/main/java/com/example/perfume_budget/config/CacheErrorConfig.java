package com.example.perfume_budget.config;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Configuration;

/**
 * A cache entry that cannot be read back (SEC-03d: a pre-deploy Java-serialised class of the old shape, a removed class,
 * a serialVersionUID change) is a MISS, not a 500: the entry is evicted so the next read rebuilds it from the
 * database. Without this every request for that key would fail until the TTL passes. Put/evict/clear failures are
 * logged (class name only) and never fail the request either; the data is still in the database.
 */
@Slf4j
@Configuration
public class CacheErrorConfig implements CachingConfigurer {

    /**
     * FIX-A2 (5): counter of cache failures the handler absorbed, tags {@code cache} (fixed names) and {@code op}
     * ({@code get|put|evict|clear}). A WARN line is easy to miss; Prometheus alert {@code CacheReadErrors} in
     * {@code prometheus-alerts.yml} fires on any {@code op="get"} increase (Redis down or entries of an unreadable shape).
     */
    public static final String ERRORS_METRIC = "shopfi.cache.errors";

    /** Resolved lazily at error time (a CachingConfigurer is created very early); null when built by hand in a test. */
    @Autowired(required = false)
    private ObjectProvider<MeterRegistry> registry;

    private void count(Cache cache, String op) {
        try {
            MeterRegistry r = registry == null ? null : registry.getIfAvailable();
            if (r != null) {
                r.counter(ERRORS_METRIC, "cache", cache.getName(), "op", op).increment();
            }
        } catch (RuntimeException ignored) {
            // metrics must never turn a cache miss into a failure
        }
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                count(cache, "get");
                log.warn("Cache '{}' entry unreadable ({}), treated as a miss and evicted", cache.getName(),
                        e.getClass().getSimpleName());
                try {
                    cache.evict(key);
                } catch (RuntimeException evictFailure) {
                    log.warn("Evicting the unreadable entry of cache '{}' failed ({})", cache.getName(),
                            evictFailure.getClass().getSimpleName());
                }
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                count(cache, "put");
                log.warn("Cache '{}' put failed ({})", cache.getName(), e.getClass().getSimpleName());
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                // an evict that did not happen leaves stale data: this must stay loud for the caller
                count(cache, "evict");
                throw e;
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                count(cache, "clear");
                throw e;
            }
        };
    }
}
