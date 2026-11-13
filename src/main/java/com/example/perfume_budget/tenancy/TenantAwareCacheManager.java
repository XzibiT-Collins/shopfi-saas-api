package com.example.perfume_budget.tenancy;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.Collection;

/**
 * Wraps the real {@link CacheManager} and transparently prefixes every cache name with the
 * current tenant ({@link TenantContext#getCachePrefix()} — tenant slug, {@code __platform__},
 * or the neutral schema). Existing {@code @Cacheable}/{@code @CacheEvict} annotations need no
 * changes; the same logical cache name maps to a per-tenant physical cache in shared Redis,
 * preventing cross-tenant cache bleed.
 */
public class TenantAwareCacheManager implements CacheManager {

    private final CacheManager delegate;

    public TenantAwareCacheManager(CacheManager delegate) {
        this.delegate = delegate;
    }

    @Override
    public Cache getCache(String name) {
        return delegate.getCache(TenantContext.getCachePrefix() + ":" + name);
    }

    @Override
    public Collection<String> getCacheNames() {
        return delegate.getCacheNames();
    }
}
