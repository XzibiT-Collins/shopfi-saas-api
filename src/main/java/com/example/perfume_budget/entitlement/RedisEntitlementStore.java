package com.example.perfume_budget.entitlement;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The production {@link EntitlementStore}: its OWN {@link StringRedisTemplate} (string keys, string JSON values; no JDK
 * serialization, no default typing), not the application's {@code RedisTemplate<String,Object>} and not a cache manager.
 * Keys are the fixed master keys of {@link EntitlementCache}; nothing is prefixed with a tenant.
 *
 * <p>The generation guard (BILL-01c) runs as two Lua scripts ({@code resources/redis/entitlement-*.lua}), each atomic on
 * the server: compare-and-set write, and increment-and-delete eviction.
 */
@Component
public class RedisEntitlementStore implements EntitlementStore {

    /** A generation counter outlives any in-flight reader (a request lasts seconds), refreshed on every eviction. */
    static final Duration GENERATION_TTL = Duration.ofDays(1);

    private static final RedisScript<Long> PUT_IF_GENERATION = script("redis/entitlement-put-if-generation.lua");
    private static final RedisScript<Long> EVICT = script("redis/entitlement-evict.lua");

    private final StringRedisTemplate redis;

    @org.springframework.beans.factory.annotation.Autowired
    public RedisEntitlementStore(RedisConnectionFactory connectionFactory) {
        StringRedisTemplate t = new StringRedisTemplate(connectionFactory);
        t.afterPropertiesSet();
        this.redis = t;
    }

    RedisEntitlementStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static RedisScript<Long> script(String path) {
        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setLocation(new ClassPathResource(path));
        s.setResultType(Long.class);
        return s;
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
    public long generation(String key) {
        String v = redis.opsForValue().get(EntitlementStore.generationKey(key));
        return v == null ? 0L : Long.parseLong(v);
    }

    @Override
    public boolean putIfGeneration(String key, String json, Duration ttl, long expectedGeneration) {
        Long written = redis.execute(PUT_IF_GENERATION, List.of(key, EntitlementStore.generationKey(key)),
                Long.toString(expectedGeneration), json, Long.toString(ttl.toMillis()));
        return written != null && written == 1L;
    }

    @Override
    public void evict(Collection<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        List<String> flat = new ArrayList<>(keys.size() * 2);
        for (String k : keys) {
            flat.add(k);
            flat.add(EntitlementStore.generationKey(k));
        }
        redis.execute(EVICT, flat, Long.toString(GENERATION_TTL.toMillis()));
    }
}
