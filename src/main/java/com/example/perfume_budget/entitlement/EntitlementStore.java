package com.example.perfume_budget.entitlement;

import java.time.Duration;
import java.util.Collection;

/**
 * The raw key/value store behind {@link EntitlementCache} (plans.md 15.1 item 1: a dedicated store, never the
 * tenant-prefixing {@code TenantAwareCacheManager}). Values are JSON strings; any method may throw on a store error, and
 * the cache then reads the database instead (never grants by default).
 */
public interface EntitlementStore {

    /** The stored JSON, or {@code null} on a miss. */
    String get(String key);

    void put(String key, String json, Duration ttl);

    void delete(Collection<String> keys);
}
