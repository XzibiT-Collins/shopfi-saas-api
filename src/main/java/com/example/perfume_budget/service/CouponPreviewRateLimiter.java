package com.example.perfume_budget.service;

import com.example.perfume_budget.exception.RateLimitExceededException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window per (tenant, customer) limiter for coupon previews so the endpoint cannot be used to guess coupon codes
 * quickly. In-memory and per instance (a multi-instance deployment multiplies the allowance by the instance count).
 */
@Component
public class CouponPreviewRateLimiter {

    private static final long WINDOW_MILLIS = 60_000L;

    private record Window(long startMillis, int count) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxPerWindow;

    public CouponPreviewRateLimiter(Clock systemClock,
                                    @Value("${checkout.coupon-preview.max-per-minute:30}") int maxPerWindow) {
        this.clock = systemClock;
        this.maxPerWindow = Math.max(1, maxPerWindow);
    }

    /** @throws RateLimitExceededException (429 + Retry-After) when the window allowance is used up */
    public void check(String tenantSlug, Long userId) {
        long now = clock.millis();
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> now - e.getValue().startMillis() >= WINDOW_MILLIS);
        }
        String key = tenantSlug + ":" + userId;
        Window w = windows.merge(key, new Window(now, 1), (old, fresh) ->
                now - old.startMillis() >= WINDOW_MILLIS ? fresh : new Window(old.startMillis(), old.count() + 1));
        if (w.count() > maxPerWindow) {
            long retry = (WINDOW_MILLIS - (now - w.startMillis()) + 999) / 1000;
            throw new RateLimitExceededException("Too many coupon checks, please wait a moment.", retry);
        }
    }
}
