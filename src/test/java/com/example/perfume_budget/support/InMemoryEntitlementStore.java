package com.example.perfume_budget.support;

import com.example.perfume_budget.entitlement.EntitlementStore;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PLAN-02 test stand-in for the Redis entitlement store (Postgres tests mock Redis). {@link Mode#OFF} (the default) is a
 * store that is always empty and accepts writes silently, so tests that edit plans or tenants with SQL never see a stale
 * entry; cache tests switch to {@link Mode#ON} (a real map, TTLs recorded) or {@link Mode#DOWN} (every call throws, like
 * Redis being unreachable) and must call {@link #reset()} afterwards.
 */
public class InMemoryEntitlementStore implements EntitlementStore {

    public enum Mode { OFF, ON, DOWN }

    public final Map<String, String> values = new ConcurrentHashMap<>();
    public final Map<String, Duration> ttls = new ConcurrentHashMap<>();
    public final Map<String, Long> generations = new ConcurrentHashMap<>();
    public volatile Mode mode = Mode.OFF;
    /** Test hook: the next N calls of {@link #evict} throw (a flaky store), counted in {@link #evictAttempts}. */
    public final java.util.concurrent.atomic.AtomicInteger failEvicts = new java.util.concurrent.atomic.AtomicInteger();
    public final java.util.concurrent.atomic.AtomicInteger evictAttempts = new java.util.concurrent.atomic.AtomicInteger();
    /** Test hook: runs right after every {@link #generation} read (the point where a loader goes to the database). */
    public volatile Runnable afterGenerationRead = null;

    public void reset() {
        mode = Mode.OFF;
        values.clear();
        ttls.clear();
        generations.clear();
        failEvicts.set(0);
        failMarks.set(0);
        evictAttempts.set(0);
        afterGenerationRead = null;
    }

    @Override
    public String get(String key) {
        down();
        return mode == Mode.ON ? values.get(key) : null;
    }

    /** Test hook: the next N calls of {@link #markEvicted} throw. */
    public final java.util.concurrent.atomic.AtomicInteger failMarks = new java.util.concurrent.atomic.AtomicInteger();

    @Override
    public synchronized void markEvicted(Collection<String> keys, String marker, Duration ttl) {
        down();
        if (failMarks.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throw new org.springframework.data.redis.RedisConnectionFailureException("mark failed (test)");
        }
        keys.forEach(k -> {
            generations.merge(k, 1L, Long::sum);
            values.put(k, marker);
            ttls.put(k, ttl);
        });
    }

    @Override
    public long generation(String key) {
        down();
        long g = mode == Mode.ON ? generations.getOrDefault(key, 0L) : 0L;
        Runnable hook = afterGenerationRead;
        if (hook != null) {
            hook.run();
        }
        return g;
    }

    /** Atomic like the Lua script (synchronized): the write lands only while the generation is unchanged. */
    @Override
    public synchronized boolean putIfGeneration(String key, String json, Duration ttl, long expectedGeneration) {
        down();
        if (mode != Mode.ON) {
            return true;
        }
        if (generations.getOrDefault(key, 0L) != expectedGeneration) {
            return false;
        }
        values.put(key, json);
        ttls.put(key, ttl);
        return true;
    }

    @Override
    public synchronized void evict(Collection<String> keys) {
        evictAttempts.incrementAndGet();
        down();
        if (failEvicts.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throw new org.springframework.data.redis.RedisConnectionFailureException("evict failed (test)");
        }
        keys.forEach(k -> {
            generations.merge(k, 1L, Long::sum);
            values.remove(k);
        });
    }

    private void down() {
        if (mode == Mode.DOWN) {
            throw new org.springframework.data.redis.RedisConnectionFailureException("redis down (test)");
        }
    }
}
