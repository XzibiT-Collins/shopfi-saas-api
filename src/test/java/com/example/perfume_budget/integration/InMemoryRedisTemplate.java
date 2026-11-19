package com.example.perfume_budget.integration;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory stand-in for the subset of Redis the OTP service uses (get, set, atomic increment, delete, expire).
 * TTLs are not enforced. Any other value operation fails loudly instead of silently doing nothing.
 */
final class InMemoryRedisTemplate extends RedisTemplate<String, Object> {

    final Map<String, Object> store = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, Object> ops = (ValueOperations<String, Object>) Proxy.newProxyInstance(
            ValueOperations.class.getClassLoader(), new Class<?>[]{ValueOperations.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "get":
                        return store.get((String) args[0]);
                    case "set":
                        store.put((String) args[0], args[1]);
                        return null;
                    case "increment":
                        return store.merge((String) args[0], 1L, (a, b) -> (Long) a + (Long) b);
                    default:
                        throw new UnsupportedOperationException("IntegrationServer in-memory Redis: " + method);
                }
            });

    @Override
    public ValueOperations<String, Object> opsForValue() {
        return ops;
    }

    @Override
    public Boolean delete(String key) {
        return store.remove(key) != null;
    }

    @Override
    public Boolean expire(String key, Duration timeout) {
        return true;
    }
}
