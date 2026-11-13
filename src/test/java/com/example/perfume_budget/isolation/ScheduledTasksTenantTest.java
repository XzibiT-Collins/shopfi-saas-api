package com.example.perfume_budget.isolation;

import com.example.perfume_budget.service.ReservedProductCleanupService;
import com.example.perfume_budget.task.DiscountCacheEvictionTask;
import com.example.perfume_budget.task.ReservedProductCleanupTask;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** TENANT-04 AC3: scheduled tasks iterate all ACTIVE tenants; a failing tenant does not stop the others. */
class ScheduledTasksTenantTest extends AbstractTenantIsolationTest {

    @Autowired TenantRunner tenantRunner;
    @Autowired CacheManager cacheManager;

    @Test
    void ac3_reservedProductCleanupRunsOncePerActiveTenantUnderThatTenantsContext() {
        tenants.acme();
        tenants.bravo();
        ReservedProductCleanupService service = mock(ReservedProductCleanupService.class);
        List<String> seen = new CopyOnWriteArrayList<>();
        when(service.cleanupExpiredReservations(any())).thenAnswer(inv -> {
            seen.add(TenantContext.getSlug() + "|" + TenantContext.getSchemaOrDefault());
            return 0;
        });

        new ReservedProductCleanupTask(service, tenantRunner, Clock.systemUTC()).cleanupExpiredReservations();

        assertThat(seen).contains("acme|tenant_acme", "bravo|tenant_bravo");
        assertThat(seen).doesNotHaveDuplicates();
        assertThat(seen).noneMatch(s -> s.startsWith("null"));
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void ac3_reservedProductCleanupFailureInOneTenantDoesNotStopOthers() {
        tenants.acme();
        tenants.bravo();
        for (String failing : List.of("acme", "bravo")) {
            ReservedProductCleanupService service = mock(ReservedProductCleanupService.class);
            List<String> completed = new CopyOnWriteArrayList<>();
            when(service.cleanupExpiredReservations(any())).thenAnswer(inv -> {
                String slug = TenantContext.getSlug();
                if (slug.equals(failing)) {
                    throw new IllegalStateException("boom in " + slug);
                }
                completed.add(slug);
                return 1;
            });

            new ReservedProductCleanupTask(service, tenantRunner, Clock.systemUTC()).cleanupExpiredReservations();

            String other = failing.equals("acme") ? "bravo" : "acme";
            assertThat(completed).as("failing=%s", failing).contains(other).doesNotContain(failing);
            assertThat(TenantContext.get()).as("context cleared after failure").isNull();
        }
    }

    @Test
    void ac3_discountCacheEvictionClearsProductCachesOfEveryActiveTenant() {
        tenants.acme();
        tenants.bravo();
        seedCaches("acme", "bravo");

        new DiscountCacheEvictionTask(cacheManager, tenantRunner).evictProductCaches();

        for (String slug : List.of("acme", "bravo")) {
            for (String name : List.of("customerProductListings", "featuredProducts", "productDetailsPage")) {
                assertThat(physical(slug + ":" + name).get("k")).as("%s:%s", slug, name).isNull();
            }
            assertThat(physical(slug + ":unrelatedCache").get("k")).as("non-product cache untouched").isNotNull();
        }
    }

    @Test
    void ac3_discountCacheEvictionFailureInOneTenantDoesNotStopOthers() {
        tenants.acme();
        tenants.bravo();
        for (String failing : List.of("acme", "bravo")) {
            seedCaches("acme", "bravo");
            CacheManager flaky = new CacheManager() {
                @Override
                public Cache getCache(String name) {
                    if (failing.equals(TenantContext.getSlug())) {
                        throw new IllegalStateException("cache down for " + failing);
                    }
                    return cacheManager.getCache(name);
                }

                @Override
                public java.util.Collection<String> getCacheNames() {
                    return cacheManager.getCacheNames();
                }
            };

            new DiscountCacheEvictionTask(flaky, tenantRunner).evictProductCaches();

            String other = failing.equals("acme") ? "bravo" : "acme";
            assertThat(physical(other + ":customerProductListings").get("k")).as("%s evicted although %s failed", other, failing).isNull();
            assertThat(physical(failing + ":customerProductListings").get("k")).as("failing tenant untouched").isNotNull();
            assertThat(TenantContext.get()).isNull();
        }
    }

    private void seedCaches(String... slugs) {
        for (String slug : slugs) {
            for (String name : List.of("customerProductListings", "featuredProducts", "productDetailsPage", "unrelatedCache")) {
                physical(slug + ":" + name).put("k", "v-" + slug);
            }
        }
    }

    /** Reaches the physical cache by its prefixed name with no tenant bound ("tenant_template:" + x is avoided by going through the delegate). */
    private Cache physical(String prefixedName) {
        String logical = prefixedName.substring(prefixedName.indexOf(':') + 1);
        String slug = prefixedName.substring(0, prefixedName.indexOf(':'));
        return tenants.runAs(slug.equals("acme") ? tenants.acme() : tenants.bravo(), () -> cacheManager.getCache(logical));
    }
}
