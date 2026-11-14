package com.example.perfume_budget.config;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** The tenant-prefixed {@code theme} cache is short-lived (bounds a read-through race after PUT); others keep the default TTL. */
class RedisConfigThemeTtlTest {

    private static Duration ttl(RedisCacheManager m, String name) {
        RedisCache c = (RedisCache) m.getCache(name);
        return c.getCacheConfiguration().getTtlFunction().getTimeToLive("k", "v");
    }

    @Test
    void themeCacheHasShortTtlForEveryTenantAndOthersKeepTheDefault() {
        RedisConfig config = new RedisConfig();
        org.springframework.test.util.ReflectionTestUtils.setField(config, "ttl", 60);
        RedisCacheManager m = config.redisCacheManager(mock(RedisConnectionFactory.class));
        assertThat(ttl(m, "acme:theme")).isEqualTo(RedisConfig.THEME_CACHE_TTL).isLessThanOrEqualTo(Duration.ofMinutes(5));
        assertThat(ttl(m, "bravo:theme")).isEqualTo(RedisConfig.THEME_CACHE_TTL);
        assertThat(ttl(m, "acme:products")).isEqualTo(Duration.ofMinutes(60));
    }
}
