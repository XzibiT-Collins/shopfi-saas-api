package com.example.perfume_budget.entitlement;

import java.time.Duration;
import java.util.Collection;

/**
 * The raw key/value store behind {@link EntitlementCache} (plans.md 15.1 item 1: a dedicated store, never the
 * tenant-prefixing {@code TenantAwareCacheManager}). Values are JSON strings; any method may throw on a store error, and
 * the cache then reads the database instead (never grants by default).
 *
 * <p>BILL-01c generation guard: every key has a generation counter ({@link #generationKey}), 0 while absent. A loader
 * reads the generation BEFORE it reads the database and stores its value with {@link #putIfGeneration}, which writes only
 * while the generation is unchanged; {@link #evict} increments the generation and deletes the key in one atomic step. So
 * a reader that read the database before a committed change can never write the old value back after the eviction.
 */
public interface EntitlementStore {

    /** The generation counter's key for a cache key: {@code plans:v1:tenant:7} -> {@code plans:v1:gen:tenant:7}. */
    static String generationKey(String key) {
        String prefix = "plans:v1:";
        return prefix + "gen:" + (key.startsWith(prefix) ? key.substring(prefix.length()) : key);
    }

    /** The stored JSON, or {@code null} on a miss. */
    String get(String key);

    /** An unconditional write (the short-lived failure marker; never a loader's value). */
    void put(String key, String json, Duration ttl);

    /** The key's current generation (0 when it was never evicted). */
    long generation(String key);

    /** Atomic compare-and-set: writes only when the key's generation still equals {@code expectedGeneration}. */
    boolean putIfGeneration(String key, String json, Duration ttl, long expectedGeneration);

    /** Atomically, per key: increments the generation and deletes the entry. */
    void evict(Collection<String> keys);
}
