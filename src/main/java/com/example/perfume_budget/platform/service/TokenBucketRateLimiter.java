package com.example.perfume_budget.platform.service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Small in-memory token bucket keyed by an arbitrary string. {@code capacity} requests are allowed in a burst and
 * the bucket refills continuously at {@code capacity / refillPeriod}. State is per JVM instance (see the PROV-02
 * PR notes: with N instances the effective limit is up to N times higher). Memory is bounded by evicting full
 * (idle) buckets once the map grows past {@link #CLEANUP_THRESHOLD}.
 */
public class TokenBucketRateLimiter {

    static final int CLEANUP_THRESHOLD = 10_000;

    private final int capacity;
    private final double refillPerNano;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(int capacity, Duration refillPeriod) {
        this(capacity, refillPeriod, System::nanoTime);
    }

    public TokenBucketRateLimiter(int capacity, Duration refillPeriod, LongSupplier nanoClock) {
        if (capacity < 1 || refillPeriod.isZero() || refillPeriod.isNegative()) {
            throw new IllegalArgumentException("capacity must be >= 1 and refillPeriod positive");
        }
        this.capacity = capacity;
        this.refillPerNano = (double) capacity / refillPeriod.toNanos();
        this.nanoClock = nanoClock;
    }

    /** @return 0 if the request is allowed (a token was taken), otherwise the seconds until a token is available. */
    public long tryAcquire(String key) {
        long now = nanoClock.getAsLong();
        if (buckets.size() > CLEANUP_THRESHOLD) {
            evictIdle(now);
        }
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        synchronized (b) {
            b.tokens = Math.min(capacity, b.tokens + (now - b.lastNanos) * refillPerNano);
            b.lastNanos = now;
            if (b.tokens >= 1.0) {
                b.tokens -= 1.0;
                return 0;
            }
            return (long) Math.ceil((1.0 - b.tokens) / refillPerNano / 1_000_000_000.0);
        }
    }

    int size() {
        return buckets.size();
    }

    private void evictIdle(long now) {
        buckets.entrySet().removeIf(e -> {
            Bucket b = e.getValue();
            synchronized (b) {
                return Math.min(capacity, b.tokens + (now - b.lastNanos) * refillPerNano) >= capacity;
            }
        });
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
