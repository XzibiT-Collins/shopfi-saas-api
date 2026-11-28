package com.example.perfume_budget.entitlement;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The production store writes plain strings with the TTL, under the exact key, and deletes exactly the keys given. */
class RedisEntitlementStoreTest {

    @Test
    @SuppressWarnings("unchecked")
    void stringValuesWithTtl_underTheExactKey() {
        StringRedisTemplate t = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(t.opsForValue()).thenReturn(ops);
        RedisEntitlementStore store = new RedisEntitlementStore(t);
        store.put("plans:v1:tenant:1", "{}", Duration.ofSeconds(60));
        verify(ops).set("plans:v1:tenant:1", "{}", Duration.ofSeconds(60));
        store.get("plans:v1:tenant:1");
        verify(ops).get("plans:v1:tenant:1");
    }
}
