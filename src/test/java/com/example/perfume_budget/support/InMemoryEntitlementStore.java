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
    public volatile Mode mode = Mode.OFF;

    public void reset() {
        mode = Mode.OFF;
        values.clear();
        ttls.clear();
    }

    @Override
    public String get(String key) {
        down();
        return mode == Mode.ON ? values.get(key) : null;
    }

    @Override
    public void put(String key, String json, Duration ttl) {
        down();
        if (mode == Mode.ON) {
            values.put(key, json);
            ttls.put(key, ttl);
        }
    }

    @Override
    public void delete(Collection<String> keys) {
        down();
        keys.forEach(values::remove);
    }

    private void down() {
        if (mode == Mode.DOWN) {
            throw new org.springframework.data.redis.RedisConnectionFailureException("redis down (test)");
        }
    }
}
