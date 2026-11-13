package com.example.perfume_budget.config;

import com.example.perfume_budget.tenancy.TenantAwareCacheManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import com.example.perfume_budget.theme.ThemeServiceImpl;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
@EnableRedisRepositories
public class RedisConfig {

    static final Duration THEME_CACHE_TTL = Duration.ofMinutes(2);

    @Value("${redis.cache.ttl.minutes:60}")
    private int ttl;

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        JdkSerializationRedisSerializer serializer = new JdkSerializationRedisSerializer();

        template.setKeySerializer(StringRedisSerializer.UTF_8);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(StringRedisSerializer.UTF_8);
        template.setHashValueSerializer(serializer);
        template.setDefaultSerializer(serializer);

        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public RedisCacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
        JdkSerializationRedisSerializer serializer = new JdkSerializationRedisSerializer();

        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(StringRedisSerializer.UTF_8))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer))
                .entryTtl(Duration.ofMinutes(ttl))
                .disableCachingNullValues();

        // The tenant-prefixed physical name is "<tenant>:<logical>", so per-cache TTLs are matched by suffix.
        // `theme` is short-lived: a read that raced a PUT may re-populate the cache with the old document
        // after the post-update evict, and this bounds that staleness to THEME_CACHE_TTL (not the default TTL).
        return new RedisCacheManager(RedisCacheWriter.nonLockingRedisCacheWriter(connectionFactory), config) {
            @Override
            protected RedisCache createRedisCache(String name, RedisCacheConfiguration cacheConfig) {
                if (name != null && name.endsWith(":" + ThemeServiceImpl.CACHE)) {
                    cacheConfig = cacheConfig.entryTtl(THEME_CACHE_TTL);
                }
                return super.createRedisCache(name, cacheConfig);
            }
        };
    }

    /**
     * Primary cache manager: tenant-prefixes every cache name so tenants never share cache
     * entries in the shared Redis. Existing @Cacheable/@CacheEvict annotations are unchanged.
     */
    @Bean
    @Primary
    public CacheManager cacheManager(RedisCacheManager redisCacheManager) {
        return new TenantAwareCacheManager(redisCacheManager);
    }

}
