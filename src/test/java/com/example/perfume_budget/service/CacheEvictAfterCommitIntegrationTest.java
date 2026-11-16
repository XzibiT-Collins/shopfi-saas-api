package com.example.perfume_budget.service;

import com.example.perfume_budget.isolation.AbstractTenantIsolationTest;
import com.example.perfume_budget.service.interfaces.InventoryManagementService;
import com.example.perfume_budget.support.TestTenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EVT-01 (b), wired: a real {@code @CacheEvict} service method ({@code releaseOrderInventory}, which the cancel /
 * expiry / settlement paths call inside the {@link TransientRetry} transaction) run in an outer transaction on the
 * real JPA transaction manager and the application's cache manager. A reader on another thread re-caches the old
 * value before the commit; the eviction runs after the commit and removes it. Attempts that roll back evict nothing.
 */
class CacheEvictAfterCommitIntegrationTest extends AbstractTenantIsolationTest {

    private static final String CACHE = "publicFeaturedProducts";

    @Autowired private InventoryManagementService inventory;
    @Autowired private CacheManager cacheManager;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private TransientRetry retry;

    private Object cached(TestTenant t) {
        return tenants.runAs(t, () -> {
            Cache.ValueWrapper w = cacheManager.getCache(CACHE).get(org.springframework.cache.interceptor.SimpleKey.EMPTY);
            return w == null ? null : w.get();
        });
    }

    private void put(TestTenant t, String value) {
        tenants.runAs(t, () -> cacheManager.getCache(CACHE).put(org.springframework.cache.interceptor.SimpleKey.EMPTY, value));
    }

    /** Another request thread (no transaction) that re-caches what it read before the commit. */
    private void concurrentReaderReCaches(TestTenant t, String value) {
        CompletableFuture.runAsync(() -> put(t, value)).join();
    }

    @Test
    void evictOfARetriedTransactionHappensAfterItsCommit() {
        TestTenant acme = tenants.acme();
        put(acme, "old");
        AtomicInteger attempts = new AtomicInteger();
        tenants.runAs(acme, () -> retry.call("evt01 evict probe", () -> new TransactionTemplate(txManager).execute(st -> {
            inventory.releaseOrderInventory("EVT01-NO-SUCH-ORDER");
            if (attempts.incrementAndGet() == 1) {
                // attempt 1 rolls back like a deadlock victim: nothing changed, so nothing may be evicted
                throw new org.springframework.dao.OptimisticLockingFailureException("injected conflict");
            }
            assertThat(cached(acme)).as("not evicted before the commit").isEqualTo("old");
            concurrentReaderReCaches(acme, "stale");
            return null;
        })));
        assertThat(attempts).hasValue(2);
        assertThat(cached(acme)).as("the stale value re-cached before the commit is gone after it").isNull();
    }

    @Test
    void rolledBackTransactionEvictsNothingAndOtherTenantsAreUntouched() {
        TestTenant acme = tenants.acme();
        TestTenant bravo = tenants.bravo();
        put(acme, "old");
        put(bravo, "bravo-old");
        try {
            tenants.runAs(acme, () -> new TransactionTemplate(txManager).executeWithoutResult(st -> {
                inventory.releaseOrderInventory("EVT01-NO-SUCH-ORDER");
                throw new IllegalStateException("rolled back");
            }));
        } catch (IllegalStateException expected) {
            // rolled back
        }
        assertThat(cached(acme)).isEqualTo("old");
        tenants.runAs(acme, () -> new TransactionTemplate(txManager).executeWithoutResult(
                st -> inventory.releaseOrderInventory("EVT01-NO-SUCH-ORDER")));
        assertThat(cached(acme)).isNull();
        assertThat(cached(bravo)).as("tenant-prefixed: bravo's cache is not acme's").isEqualTo("bravo-old");
    }
}
