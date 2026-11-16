package com.example.perfume_budget.tenancy;

import com.example.perfume_budget.config.RedisConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * EVT-01 (b): a cache eviction requested inside a transaction (a {@code @CacheEvict} method joining the caller's /
 * retry wrapper's transaction) happens AFTER that transaction commits. Evicting before the commit let a concurrent
 * reader, which still sees the old rows, re-cache them in the gap, and the stale entry then outlived the commit.
 * A rolled-back attempt evicts nothing (nothing changed); outside a transaction eviction stays immediate.
 */
class TenantAwareCacheManagerAfterCommitTest {

    static class NoopTxManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object t, TransactionDefinition d) { }
        @Override protected void doCommit(DefaultTransactionStatus s) { }
        @Override protected void doRollback(DefaultTransactionStatus s) { }
    }

    private CacheManager manager;
    private final TransactionTemplate tx = new TransactionTemplate(new NoopTxManager());

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantIdentity.tenant("acme", "tenant_acme"));
        manager = new TenantAwareCacheManager(new ConcurrentMapCacheManager());
        manager.getCache("productDetailsPage").put("oud", "old");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Cache cache() {
        return manager.getCache("productDetailsPage");
    }

    /** A reader on another thread (no transaction) that still sees the old rows and re-caches them. */
    private void concurrentReaderReCaches(String value) {
        TenantIdentity t = TenantContext.get();
        CompletableFuture.runAsync(() -> {
            TenantContext.set(t);
            try {
                manager.getCache("productDetailsPage").put("oud", value);
            } finally {
                TenantContext.clear();
            }
        }).join();
    }

    private Object cached() {
        Cache.ValueWrapper w = cache().get("oud");
        return w == null ? null : w.get();
    }

    @Test
    void evictInsideATransactionWaitsForTheCommit_soAStaleReCacheBeforeTheCommitIsRemoved() {
        tx.executeWithoutResult(st -> {
            cache().evict("oud");
            assertThat(cached()).as("not evicted before the commit").isEqualTo("old");
            concurrentReaderReCaches("stale");
        });
        assertThat(cached()).as("the post-commit evict removed the re-cached stale entry").isNull();
    }

    @Test
    void clearInsideATransactionWaitsForTheCommit() {
        tx.executeWithoutResult(st -> {
            cache().clear();
            concurrentReaderReCaches("stale");
            assertThat(cached()).isEqualTo("stale");
        });
        assertThat(cached()).isNull();
    }

    @Test
    void rolledBackAttemptsEvictNothingAndTheCommittingRetryEvictsOnce() {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                tx.executeWithoutResult(st -> {
                    cache().clear();
                    throw new IllegalStateException("deadlock");
                });
            } catch (IllegalStateException expected) {
                // retried
            }
            assertThat(cached()).as("attempt %d rolled back: nothing changed, nothing evicted", attempt).isEqualTo("old");
        }
        tx.executeWithoutResult(st -> cache().clear());
        assertThat(cached()).isNull();
    }

    @Test
    void putInsideATransactionIsVisibleOnlyAfterTheCommit() {
        tx.executeWithoutResult(st -> {
            cache().put("new", "uncommitted");
            assertThat(cache().get("new")).isNull();
        });
        assertThat(cache().get("new").get()).isEqualTo("uncommitted");
    }

    @Test
    void outsideATransactionEvictionIsImmediate() {
        cache().evict("oud");
        assertThat(cached()).isNull();
    }

    @Test
    void theProductionCacheManagerIsTransactionAwareAndStillTenantPrefixed() {
        RedisConfig config = new RedisConfig();
        org.springframework.test.util.ReflectionTestUtils.setField(config, "ttl", 60);
        RedisCacheManager redis = config.redisCacheManager(mock(RedisConnectionFactory.class));
        Cache c = config.cacheManager(redis).getCache("productDetailsPage");
        assertThat(c).isInstanceOf(TransactionAwareCacheDecorator.class);
        assertThat(c.getName()).isEqualTo("acme:productDetailsPage");
    }
}
