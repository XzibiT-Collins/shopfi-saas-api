package com.example.perfume_budget.theme;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-admin token bucket for {@code PUT /api/v1/theme} (contract 7.4: 30/min/admin). Small, bounded, in-memory:
 * no rate limiter existed on the base branch. Keys are {@code tenantSlug:adminEmail}; the map is an access-ordered
 * LRU capped at {@link #MAX_KEYS} and idle buckets (full again) are dropped on insert, so memory cannot grow
 * with attacker-chosen keys (keys only exist for authenticated admins anyway). Per-instance (not cluster-wide).
 */
@Component
public class ThemeWriteRateLimiter {

    static final int MAX_KEYS = 10_000;

    private static final long MINUTE_NANOS = 60_000_000_000L;

    private final int perMinute;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
            return size() > MAX_KEYS;
        }
    };

    @Autowired
    public ThemeWriteRateLimiter(@Value("${theme.put-rate-limit.per-minute:30}") int perMinute) {
        this(perMinute, Clock.systemUTC());
    }

    ThemeWriteRateLimiter(int perMinute, Clock clock) {
        if (perMinute < 1) {
            throw new IllegalArgumentException("perMinute must be >= 1");
        }
        this.perMinute = perMinute;
        this.clock = clock;
    }

    private static final class Bucket {
        double tokens;
        long lastNanos;
    }

    /** @return 0 when the request is allowed (a token was taken), else seconds to wait (>= 1). */
    public synchronized long tryAcquire(String key) {
        long now = nanos();
        Bucket b = buckets.get(key);
        if (b == null) {
            if (buckets.size() >= MAX_KEYS / 2) {
                evictIdle(now);
            }
            b = new Bucket();
            b.tokens = perMinute;
            b.lastNanos = now;
            buckets.put(key, b);
        } else {
            double refill = (now - b.lastNanos) * (perMinute / (double) MINUTE_NANOS);
            b.tokens = Math.min(perMinute, b.tokens + refill);
            b.lastNanos = now;
        }
        if (b.tokens >= 1.0) {
            b.tokens -= 1.0;
            return 0;
        }
        double missing = 1.0 - b.tokens;
        return Math.max(1, (long) Math.ceil(missing * MINUTE_NANOS / perMinute / 1_000_000_000d));
    }

    private void evictIdle(long now) {
        buckets.values().removeIf(b -> b.tokens + (now - b.lastNanos) * (perMinute / (double) MINUTE_NANOS) >= perMinute);
    }

    /** Test hook. */
    public synchronized void reset() {
        buckets.clear();
    }

    synchronized int size() {
        return buckets.size();
    }

    private long nanos() {
        return clock.millis() * 1_000_000L;
    }
}
