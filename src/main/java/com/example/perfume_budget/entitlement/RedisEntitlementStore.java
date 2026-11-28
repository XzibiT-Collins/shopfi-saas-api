package com.example.perfume_budget.entitlement;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
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

    private final StringRedisTemplate redis;

    @org.springframework.beans.factory.annotation.Autowired
    public RedisEntitlementStore(RedisConnectionFactory shared) {
        LettuceConnectionFactory own = dedicated(shared);
        this.ownFactory = own;
        StringRedisTemplate t = new StringRedisTemplate(own != null ? own : shared);
        t.afterPropertiesSet();
        this.redis = t;
    }

    /** Lets Spring close the dedicated client with the context. */
    @jakarta.annotation.PreDestroy
    void close() {
        if (ownFactory != null) {
            ownFactory.destroy();
        }
    }

    /**
     * BILL-01c (QA M1): the store gets its OWN Lettuce client, never the application's shared one, so a Redis outage cannot
     * hold a request thread for the library's default 60 s per command. Same host, credentials and TLS as the shared factory,
     * but a short connect and command timeout, and commands issued while disconnected are rejected at once (nothing is buffered
     * and waits). {@code null} when the shared factory is not Lettuce (tests mock it).
     */
    static LettuceConnectionFactory dedicated(RedisConnectionFactory shared) {
        if (!(shared instanceof LettuceConnectionFactory lcf) || lcf.getStandaloneConfiguration() == null) {
            return null;
        }
        LettuceClientConfiguration.LettuceClientConfigurationBuilder b = LettuceClientConfiguration.builder()
                .commandTimeout(COMMAND_TIMEOUT)
                .clientOptions(clientOptions());
        if (lcf.isUseSsl()) {
            b.useSsl();
            if (!lcf.isVerifyPeer()) {
                b.useSsl().disablePeerVerification();
            }
        }
        LettuceConnectionFactory f = new LettuceConnectionFactory(lcf.getStandaloneConfiguration(), b.build());
        f.afterPropertiesSet();
        return f;
    }

    static ClientOptions clientOptions() {
        return ClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(COMMAND_TIMEOUT).build())
                .timeoutOptions(TimeoutOptions.enabled(COMMAND_TIMEOUT))
                .build();
    }

    /** Every command waits at most this long (connect included); while disconnected a command fails at once. */
    static final Duration COMMAND_TIMEOUT = Duration.ofMillis(300);

    private LettuceConnectionFactory ownFactory;

    RedisEntitlementStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public String get(String key) {
        return redis.opsForValue().get(key);
    }

    @Override
    public void markEvicted(Collection<String> keys, String marker, Duration ttl) {
        if (keys.isEmpty()) {
            return;
        }
        List<String> flat = new ArrayList<>(keys.size() * 2);
        for (String k : keys) {
            flat.add(k);
            flat.add(EntitlementStore.generationKey(k));
        }
        redis.execute(RedisEntitlementScripts.MARK_EVICTED, flat, Long.toString(RedisEntitlementScripts.GENERATION_TTL.toMillis()),
                marker, Long.toString(ttl.toMillis()));
    }

    @Override
    public long generation(String key) {
        String v = redis.opsForValue().get(EntitlementStore.generationKey(key));
        return v == null ? 0L : Long.parseLong(v);
    }

    @Override
    public boolean putIfGeneration(String key, String json, Duration ttl, long expectedGeneration) {
        Long written = redis.execute(RedisEntitlementScripts.PUT_IF_GENERATION, List.of(key, EntitlementStore.generationKey(key)),
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
        redis.execute(RedisEntitlementScripts.EVICT, flat, Long.toString(RedisEntitlementScripts.GENERATION_TTL.toMillis()));
    }
}
