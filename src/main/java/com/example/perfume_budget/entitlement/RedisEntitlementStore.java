package com.example.perfume_budget.entitlement;

import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;

/**
 * The production {@link EntitlementStore}: its OWN {@link StringRedisTemplate} (string keys, string JSON values; no JDK
 * serialization, no default typing), not the application's {@code RedisTemplate<String,Object>} and not a cache manager.
 * Keys are the fixed master keys of {@link EntitlementCache}; nothing is prefixed with a tenant.
 */
@Component
public class RedisEntitlementStore implements EntitlementStore {

    private final StringRedisTemplate redis;

    public RedisEntitlementStore(RedisConnectionFactory connectionFactory) {
        StringRedisTemplate t = new StringRedisTemplate(connectionFactory);
        t.afterPropertiesSet();
        this.redis = t;
    }

    RedisEntitlementStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public String get(String key) {
        return redis.opsForValue().get(key);
    }

    @Override
    public void put(String key, String json, Duration ttl) {
        redis.opsForValue().set(key, json, ttl);
    }

    @Override
    public void delete(Collection<String> keys) {
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
