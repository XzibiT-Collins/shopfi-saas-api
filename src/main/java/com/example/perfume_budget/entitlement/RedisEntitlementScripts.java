package com.example.perfume_budget.entitlement;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;

/** The two Lua scripts of the generation guard ({@code resources/redis/entitlement-*.lua}) and their constants. */
final class RedisEntitlementScripts {

    /** A generation counter outlives any in-flight reader (a request lasts seconds), refreshed on every eviction. */
    static final Duration GENERATION_TTL = Duration.ofDays(1);

    static final RedisScript<Long> PUT_IF_GENERATION = script("redis/entitlement-put-if-generation.lua");
    static final RedisScript<Long> MARK_EVICTED = script("redis/entitlement-mark-evicted.lua");
    static final RedisScript<Long> EVICT = script("redis/entitlement-evict.lua");

    private RedisEntitlementScripts() {
    }

    private static RedisScript<Long> script(String path) {
        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setLocation(new ClassPathResource(path));
        s.setResultType(Long.class);
        return s;
    }
}
