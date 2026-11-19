package com.example.perfume_budget.integration;

import com.example.perfume_budget.config.TenantSeeder;
import com.example.perfume_budget.platform.repository.TenantRepository;
import com.example.perfume_budget.service.interfaces.PaymentSettingsService;
import com.example.perfume_budget.support.FakeDnsTxtResolver;
import com.example.perfume_budget.support.TestTenants;
import com.example.perfume_budget.tenancy.TenantAwareCacheManager;
import com.example.perfume_budget.tenancy.TenantSchemaMigrator;
import org.mockito.Mockito;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Test-scope wiring of {@link IntegrationServer}. A {@code @TestConfiguration}, so component scanning of the
 * application (and therefore every Spring test) never picks it up; only the launcher registers it explicitly.
 * Redis is in memory, mail is captured, DNS is fake; Postgres, Flyway, tenancy, security, CORS and every controller are
 * the real production beans.
 */
@TestConfiguration
class IntegrationServerConfig {

    @Bean
    RedisConnectionFactory redisConnectionFactory() {
        return Mockito.mock(RedisConnectionFactory.class);
    }

    @Bean
    ReactiveRedisConnectionFactory reactiveRedisConnectionFactory() {
        return Mockito.mock(ReactiveRedisConnectionFactory.class);
    }

    @Bean
    static BeanPostProcessor integrationInMemoryRedis() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if ("cacheManager".equals(beanName) && bean instanceof TenantAwareCacheManager) {
                    return new TenantAwareCacheManager(new ConcurrentMapCacheManager());
                }
                if ("redisTemplate".equals(beanName) && bean instanceof RedisTemplate<?, ?> orig) {
                    InMemoryRedisTemplate t = new InMemoryRedisTemplate();
                    t.setConnectionFactory(orig.getConnectionFactory());
                    t.afterPropertiesSet();
                    return t;
                }
                return bean;
            }
        };
    }

    @Bean
    @Primary
    CapturingMailTransport capturingMailTransport() {
        return new CapturingMailTransport();
    }

    @Bean
    @Primary
    FakeDnsTxtResolver fakeDnsTxtResolver() {
        return new FakeDnsTxtResolver();
    }

    @Bean
    ApplicationRunner seedAndServeControl(TenantRepository tenantRepository, TenantSchemaMigrator migrator, TenantSeeder seeder,
                                          JdbcTemplate jdbc, PasswordEncoder encoder, PaymentSettingsService paymentSettings,
                                          CapturingMailTransport mail, Environment env) {
        return args -> {
            IntegrationSeeder.seed(new TestTenants(tenantRepository, migrator, seeder), jdbc, encoder, paymentSettings);
            new ControlServer(env.getRequiredProperty("integration.control-port", Integer.class),
                    env.getRequiredProperty("integration.frontend-port", Integer.class), mail, jdbc).start();
            System.out.println("INTEGRATION_SERVER_READY");
        };
    }
}
