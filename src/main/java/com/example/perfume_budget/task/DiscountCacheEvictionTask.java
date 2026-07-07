package com.example.perfume_budget.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Product/shop discounts are date-windowed and resolved dynamically on read, but product
 * listings/details are cached (Redis, ~60min TTL). This task periodically clears those caches so a
 * discount that just started or expired surfaces/reverts promptly without an admin write.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiscountCacheEvictionTask {
    private static final List<String> PRODUCT_CACHES =
            List.of("customerProductListings", "featuredProducts", "productDetailsPage");

    private final CacheManager cacheManager;

    @Scheduled(fixedDelayString = "${discount.cache-evict-interval-ms:600000}")
    public void evictProductCaches() {
        PRODUCT_CACHES.forEach(name -> {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        });
        log.debug("Discount cache eviction cleared product caches");
    }
}
