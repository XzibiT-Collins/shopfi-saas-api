package com.example.perfume_budget.support;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.example.perfume_budget.config.TenantSeeder;
import com.example.perfume_budget.platform.repository.TenantRepository;
import com.example.perfume_budget.tenancy.TenantAwareCacheManager;
import com.example.perfume_budget.tenancy.TenantSchemaMigrator;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Base class for tests that boot the FULL application against a real PostgreSQL with the production
 * multi-tenant wiring: Flyway migrates {@code master} and {@code tenant_template} before Hibernate
 * runs with {@code ddl-auto=validate}.
 *
 * <ul>
 *   <li>DB connection: see {@link PostgresTestDatabase} (env vars + defaults). Skipped with a reason when
 *       unreachable, unless {@code SHOPFI_TEST_DB_REQUIRED=true}.</li>
 *   <li>Before the first context boot all app schemas are dropped (so boot really starts from an empty DB),
 *       and a JVM shutdown hook drops them again afterwards.</li>
 *   <li>Redis is mocked (no server needed); caching uses {@link TenantAwareCacheManager} over an in-memory
 *       delegate, so cache names are tenant-prefixed exactly like production. Autowire {@code CacheManager}
 *       and inspect the delegate's cache names.</li>
 *   <li>Autowire {@link TestTenants} to get the {@code acme} / {@code bravo} tenants.</li>
 * </ul>
 * Contexts with identical configuration are cached by Spring, so every subclass shares one boot.
 * Subclasses must not use {@code @DirtiesContext} unless they accept a re-boot (schemas then already exist).
 */
@SpringBootTest
@ActiveProfiles({"test", "pgtest"})
@ExtendWith(PostgresAvailableCondition.class)
@Import(AbstractPostgresIntegrationTest.PostgresTestConfig.class)
public abstract class AbstractPostgresIntegrationTest {

    /** Whether the app schemas were absent when the first context started booting. */
    static final AtomicReference<Boolean> EMPTY_DB_AT_FIRST_BOOT = new AtomicReference<>();

    static {
        if (PostgresTestDatabase.unreachableReason() == null) {
            PostgresTestDatabase.dropAppSchemas();
            EMPTY_DB_AT_FIRST_BOOT.set(!PostgresTestDatabase.schemaExists("master")
                    && !PostgresTestDatabase.schemaExists("tenant_template"));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    PostgresTestDatabase.dropAppSchemas();
                } catch (RuntimeException ignored) {
                    // best effort
                }
            }, "shopfi-test-schema-cleanup"));
        }
    }

    @MockitoBean
    protected RedisConnectionFactory redisConnectionFactory;

    @MockitoBean
    protected ReactiveRedisConnectionFactory reactiveRedisConnectionFactory;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> PostgresTestDatabase.URL);
        registry.add("spring.datasource.username", () -> PostgresTestDatabase.USER);
        registry.add("spring.datasource.password", () -> PostgresTestDatabase.PASSWORD);
    }

    @TestConfiguration
    static class PostgresTestConfig {

        /**
         * RedisConfig already declares @Primary CacheManager beans, so rather than adding a competing
         * one this swaps the production {@code cacheManager} bean (a TenantAwareCacheManager over Redis)
         * for the same wrapper over an in-memory delegate.
         */
        @Bean
        static BeanPostProcessor inMemoryCacheManagerSwap() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if ("cacheManager".equals(beanName) && bean instanceof TenantAwareCacheManager) {
                        return new TenantAwareCacheManager(new ConcurrentMapCacheManager());
                    }
                    return bean;
                }
            };
        }

        @Bean
        TestTenants testTenants(TenantRepository repo, TenantSchemaMigrator migrator, TenantSeeder seeder) {
            return new TestTenants(repo, migrator, seeder);
        }

        @Bean
        static BoundaryRecorder emfBoundaryRecorder(BeanFactory beanFactory) {
            return new BoundaryRecorder(beanFactory);
        }
    }

    /**
     * Records, at the instant Hibernate's EntityManagerFactory starts building (and therefore validating),
     * whether the master and tenant_template schemas already contained their migrated tables. Used by
     * {@code MigrationBeforeValidationTest}.
     */
    public static class BoundaryRecorder implements BeanPostProcessor {
        public static final AtomicReference<Boolean> MIGRATED_BEFORE_EMF = new AtomicReference<>();
        private final BeanFactory beanFactory;

        BoundaryRecorder(BeanFactory beanFactory) {
            this.beanFactory = beanFactory;
        }

        @Override
        public Object postProcessBeforeInitialization(Object bean, String beanName) {
            if ("entityManagerFactory".equals(beanName)) {
                try (Connection c = beanFactory.getBean(DataSource.class).getConnection();
                     var ps = c.prepareStatement("SELECT count(*) FROM information_schema.tables "
                             + "WHERE (table_schema = 'master' AND table_name = 'tenants') "
                             + "OR (table_schema = 'tenant_template' AND table_name = 'products')")) {
                    try (var rs = ps.executeQuery()) {
                        rs.next();
                        MIGRATED_BEFORE_EMF.set(rs.getLong(1) == 2);
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
            return bean;
        }
    }
}
