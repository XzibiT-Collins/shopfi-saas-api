package com.example.perfume_budget.tenancy;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;

import java.util.Collection;

/**
 * Wraps the real {@link CacheManager} and transparently prefixes every cache name with the
 * current tenant ({@link TenantContext#getCachePrefix()} — tenant slug, {@code __platform__},
 * or the neutral schema). Existing {@code @Cacheable}/{@code @CacheEvict} annotations need no
 * changes; the same logical cache name maps to a per-tenant physical cache in shared Redis,
 * preventing cross-tenant cache bleed.
 *
 * <p>EVT-01: every cache it hands out is transaction-aware ({@link TransactionAwareCacheDecorator}). Inside a
 * transaction, {@code evict} / {@code clear} (a {@code @CacheEvict} method joining the caller's or the
 * {@code TransientRetry} wrapper's transaction) and {@code put} run AFTER the commit, and not at all if it rolls back.
 * Evicting before the commit let a concurrent reader, which still sees the old rows, re-cache them in the gap, and
 * that stale entry then outlived the commit; a rolled-back attempt of a retried transaction changed nothing, so it
 * evicts nothing. Outside a transaction they run at once, as before. Reads, {@code putIfAbsent} and
 * {@code invalidate} are always immediate.
 */
public class TenantAwareCacheManager implements CacheManager {

    private final CacheManager delegate;

    public TenantAwareCacheManager(CacheManager delegate) {
        this.delegate = delegate;
    }

    @Override
    public Cache getCache(String name) {
        Cache cache = delegate.getCache(TenantContext.getCachePrefix() + ":" + name);
        return cache == null ? null : new TransactionAwareCacheDecorator(cache);
    }

    @Override
    public Collection<String> getCacheNames() {
        return delegate.getCacheNames();
    }
}
