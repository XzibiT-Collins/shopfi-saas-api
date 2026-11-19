package com.example.perfume_budget.config;

import lombok.extern.slf4j.Slf4j;
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

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
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
                log.warn("Cache '{}' put failed ({})", cache.getName(), e.getClass().getSimpleName());
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                // an evict that did not happen leaves stale data: this must stay loud for the caller
                throw e;
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                throw e;
            }
        };
    }
}
