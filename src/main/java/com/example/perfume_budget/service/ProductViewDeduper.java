package com.example.perfume_budget.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * VIEW-01: at most one counted view per (tenant, product, client key) per window (default 30 minutes).
 *
 * <p>In memory and per instance. <b>Bounded</b>: at most {@code maxEntries} keys, oldest first. Entries are kept in
 * insertion order and a repeat does not re-insert (the window runs from the first counted view), so the eldest entry is
 * always the one that expires first: expired entries are dropped from the head in amortised O(1), and when the map is
 * still full the eldest live entry is evicted (its visitor could then be counted once more: bounded inflation, never
 * unbounded memory). Keys hold the tenant slug, the product id and the client key; the address never leaves memory.
 */
@Component
public class ProductViewDeduper {

    private final Clock clock;
    private final long windowMillis;
    private final int maxEntries;
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<>();

    public ProductViewDeduper(Clock systemClock,
                              @Value("${catalog.views.dedupe-minutes:30}") int windowMinutes,
                              @Value("${catalog.views.dedupe-max-entries:50000}") int maxEntries) {
        this.clock = systemClock;
        this.windowMillis = Math.max(1, windowMinutes) * 60_000L;
        this.maxEntries = Math.max(1, maxEntries);
    }

    /** @return true when this is the first view of the key inside the window (and records it), false for a repeat */
    public boolean firstView(String tenantSlug, long productId, String clientKey) {
        long now = clock.millis();
        String key = tenantSlug + '|' + productId + '|' + clientKey;
        synchronized (seen) {
            dropExpired(now);
            if (seen.containsKey(key)) {
                return false;
            }
            while (seen.size() >= maxEntries) {
                Iterator<String> it = seen.keySet().iterator();
                it.next();
                it.remove();
            }
            seen.put(key, now + windowMillis);
            return true;
        }
    }

    private void dropExpired(long now) {
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext() && it.next().getValue() <= now) {
            it.remove();
        }
    }

    int size() {
        synchronized (seen) {
            return seen.size();
        }
    }
}
