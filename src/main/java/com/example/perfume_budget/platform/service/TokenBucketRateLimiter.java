package com.example.perfume_budget.platform.service;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Bounded in-memory token bucket keyed by an arbitrary string. {@code capacity} requests are allowed in a burst and
 * the bucket refills continuously at {@code capacity / refillPeriod}. State is per JVM instance (with N instances
 * the effective limit is up to N times higher).
 *
 * <p><b>Memory and CPU bound.</b> At most {@code maxKeys} buckets are tracked. They live in an access-ordered
 * {@link LinkedHashMap}, so the least recently used bucket is always first. On every call the idle (fully refilled,
 * hence indistinguishable from "never seen") buckets at the head are dropped; this is amortised O(1) because each
 * bucket is removed at most once, and there is no scan of the whole map. Eviction never drops a bucket that still
 * owes tokens, since that would let an attacker reset a victim's (or its own) limit by flooding unique keys.
 * <b>Fail closed:</b> when the map is at {@code maxKeys} and the head bucket is not yet idle, a new key is not
 * tracked; it is served from one shared overflow bucket (same capacity and refill), so a key flood degrades to a
 * global limit instead of growing memory or bypassing the limit. One short critical section per call, no I/O.
 */
public class TokenBucketRateLimiter {

    public static final int DEFAULT_MAX_KEYS = 10_000;

    private final int capacity;
    private final double refillPerNano;
    private final int maxKeys;
    private final LongSupplier nanoClock;
    private final LinkedHashMap<String, Bucket> buckets = new LinkedHashMap<>(16, 0.75f, true);
    private final Bucket overflow;
    private final Object lock = new Object();

    public TokenBucketRateLimiter(int capacity, Duration refillPeriod) {
        this(capacity, refillPeriod, DEFAULT_MAX_KEYS, System::nanoTime);
    }

    public TokenBucketRateLimiter(int capacity, Duration refillPeriod, LongSupplier nanoClock) {
        this(capacity, refillPeriod, DEFAULT_MAX_KEYS, nanoClock);
    }

    public TokenBucketRateLimiter(int capacity, Duration refillPeriod, int maxKeys, LongSupplier nanoClock) {
        if (capacity < 1 || refillPeriod.isZero() || refillPeriod.isNegative() || maxKeys < 1) {
            throw new IllegalArgumentException("capacity and maxKeys must be >= 1 and refillPeriod positive");
        }
        this.capacity = capacity;
        this.refillPerNano = (double) capacity / refillPeriod.toNanos();
        this.maxKeys = maxKeys;
        this.nanoClock = nanoClock;
        this.overflow = new Bucket(capacity, nanoClock.getAsLong());
    }

    /** @return 0 if the request is allowed (a token was taken), otherwise the seconds until a token is available. */
    public long tryAcquire(String key) {
        long now = nanoClock.getAsLong();
        synchronized (lock) {
            evictIdleHead(now);
            Bucket b = buckets.get(key); // access-order: marks it most recently used
            if (b == null) {
                if (buckets.size() >= maxKeys) {
                    b = overflow; // fail closed
                } else {
                    b = new Bucket(capacity, now);
                    buckets.put(key, b);
                }
            }
            return take(b, now);
        }
    }

    private long take(Bucket b, long now) {
        b.tokens = Math.min(capacity, b.tokens + (now - b.lastNanos) * refillPerNano);
        b.lastNanos = now;
        if (b.tokens >= 1.0) {
            b.tokens -= 1.0;
            return 0;
        }
        return (long) Math.ceil((1.0 - b.tokens) / refillPerNano / 1_000_000_000.0);
    }

    /** Drops fully refilled buckets from the least-recently-used end; stops at the first one still in debt. */
    private void evictIdleHead(long now) {
        Iterator<Map.Entry<String, Bucket>> it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            Bucket b = it.next().getValue();
            if (b.tokens + (now - b.lastNanos) * refillPerNano >= capacity) {
                it.remove();
            } else {
                return;
            }
        }
    }

    int size() {
        synchronized (lock) {
            return buckets.size();
        }
    }

    private static final class Bucket {
        double tokens;
        long lastNanos;

        Bucket(double tokens, long lastNanos) {
            this.tokens = tokens;
            this.lastNanos = lastNanos;
        }
    }
}
