package com.example.perfume_budget.service;

import com.example.perfume_budget.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * SEC-03c residual: {@code GET /product/{slug}} records a product view, i.e. a safe method has a (counter-only) write
 * side effect. To keep reloads, prefetches and scripted GET loops from inflating {@code view_count}, a view is counted
 * at most once per (tenant, product, client key) per window (default 30 minutes). The client key is
 * {@code ClientIps#rateLimitKey} (IPv4, or the IPv6 /64), so clients behind one NAT count once per window.
 *
 * <p>In-memory, per instance, bounded LRU ({@code catalog.product-view.max-entries}); when full the oldest entries are
 * evicted, which can only let a duplicate count again, never block a first view. With N instances behind a load
 * balancer a client can count up to N times per window: an analytics counter, not a security control.
 */
@Component
public class ProductViewDeduplicator {

    private final long windowNanos;
    private final LongSupplier clock;
    private final Map<String, Long> seen;

    @Autowired
    public ProductViewDeduplicator(@Value("${catalog.product-view.dedupe-window-seconds:1800}") long windowSeconds,
                                   @Value("${catalog.product-view.max-entries:100000}") int maxEntries) {
        this(windowSeconds, maxEntries, System::nanoTime);
    }

    ProductViewDeduplicator(long windowSeconds, int maxEntries, LongSupplier clock) {
        this.windowNanos = Duration.ofSeconds(Math.max(0, windowSeconds)).toNanos();
        this.clock = clock;
        int max = Math.max(1, maxEntries);
        this.seen = new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > max;
            }
        };
    }

    /** True when this client has not had a view of this product (in the current tenant) counted within the window. */
    public boolean firstView(Long productId, String clientKey) {
        if (productId == null) {
            return false;
        }
        String key = TenantContext.getCachePrefix() + "|" + productId + "|" + (clientKey == null ? "-" : clientKey);
        long now = clock.getAsLong();
        synchronized (seen) {
            Long countedAt = seen.get(key);
            if (countedAt != null && now - countedAt < windowNanos) {
                return false;
            }
            seen.remove(key); // re-insert so the entry moves to the young end (insertion order)
            seen.put(key, now);
            return true;
        }
    }
}
