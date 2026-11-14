package com.example.perfume_budget.support;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * In-memory stand-in for the subset of Redis the OTP service uses (get, set with TTL, atomic increment, delete,
 * expire). TTLs are recorded but not enforced. Wire it onto a Mockito mock or spy of {@code RedisTemplate<String,Object>}:
 * the keys then land in {@link #store} so tests can assert on the exact (tenant scoped) key names.
 */
public final class FakeRedis {

    public final Map<String, Object> store = new ConcurrentHashMap<>();
    public final Map<String, Duration> ttls = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public static FakeRedis wire(RedisTemplate<String, Object> template) {
        FakeRedis fake = new FakeRedis();
        ValueOperations<String, Object> ops = mock(ValueOperations.class);
        lenient().doReturn(ops).when(template).opsForValue();
        lenient().when(ops.get(any())).thenAnswer(i -> fake.store.get((String) i.getArgument(0)));
        lenient().doAnswer(i -> {
            fake.store.put(i.getArgument(0), i.getArgument(1));
            fake.ttls.put(i.getArgument(0), i.getArgument(2));
            return null;
        }).when(ops).set(anyString(), any(), any(Duration.class));
        lenient().when(ops.increment(anyString())).thenAnswer(i -> (Long) fake.store.merge(
                (String) i.getArgument(0), 1L, (a, b) -> (Long) a + (Long) b));
        lenient().doAnswer(i -> fake.store.remove((String) i.getArgument(0)) != null).when(template).delete(anyString());
        lenient().doAnswer(i -> {
            fake.ttls.put(i.getArgument(0), i.getArgument(1));
            return true;
        }).when(template).expire(anyString(), any(Duration.class));
        return fake;
    }
}
