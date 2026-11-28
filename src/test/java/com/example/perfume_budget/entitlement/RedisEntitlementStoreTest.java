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
        store.get("plans:v1:tenant:1");
        verify(ops).get("plans:v1:tenant:1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void generationIsReadFromItsOwnKey_zeroWhenAbsent() {
        StringRedisTemplate t = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(t.opsForValue()).thenReturn(ops);
        when(ops.get("plans:v1:gen:tenant:7")).thenReturn("5");
        RedisEntitlementStore store = new RedisEntitlementStore(t);
        org.assertj.core.api.Assertions.assertThat(store.generation("plans:v1:tenant:7")).isEqualTo(5);
        org.assertj.core.api.Assertions.assertThat(store.generation("plans:v1:tenant:8")).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theGuardedWriteIsOneAtomicScriptOverTheKeyAndItsGeneration() {
        StringRedisTemplate t = mock(StringRedisTemplate.class);
        when(t.execute(org.mockito.ArgumentMatchers.any(org.springframework.data.redis.core.script.RedisScript.class),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.eq("3"), org.mockito.ArgumentMatchers.eq("{}"),
                org.mockito.ArgumentMatchers.eq("60000"))).thenReturn(1L, 0L);
        RedisEntitlementStore store = new RedisEntitlementStore(t);
        org.assertj.core.api.Assertions.assertThat(store.putIfGeneration("plans:v1:tenant:7", "{}", Duration.ofSeconds(60), 3)).isTrue();
        org.assertj.core.api.Assertions.assertThat(store.putIfGeneration("plans:v1:tenant:7", "{}", Duration.ofSeconds(60), 3)).isFalse();
        org.mockito.ArgumentCaptor<List<String>> keys = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(t, org.mockito.Mockito.times(2)).execute(org.mockito.ArgumentMatchers.any(org.springframework.data.redis.core.script.RedisScript.class),
                keys.capture(), org.mockito.ArgumentMatchers.eq("3"), org.mockito.ArgumentMatchers.eq("{}"), org.mockito.ArgumentMatchers.eq("60000"));
        org.assertj.core.api.Assertions.assertThat(keys.getValue()).containsExactly("plans:v1:tenant:7", "plans:v1:gen:tenant:7");
    }

    @Test
    @SuppressWarnings("unchecked")
    void evictionIsOneAtomicScriptThatBumpsTheGenerationAndDeletesEveryKey() {
        StringRedisTemplate t = mock(StringRedisTemplate.class);
        RedisEntitlementStore store = new RedisEntitlementStore(t);
        store.evict(List.of("plans:v1:entitlements:2", "plans:v1:tenant:7"));
        org.mockito.ArgumentCaptor<List<String>> keys = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(t).execute(org.mockito.ArgumentMatchers.any(org.springframework.data.redis.core.script.RedisScript.class),
                keys.capture(), org.mockito.ArgumentMatchers.eq("86400000"));
        org.assertj.core.api.Assertions.assertThat(keys.getValue()).containsExactly("plans:v1:entitlements:2",
                "plans:v1:gen:entitlements:2", "plans:v1:tenant:7", "plans:v1:gen:tenant:7");
        store.evict(List.of());
        org.mockito.Mockito.verifyNoMoreInteractions(t);
    }

    @Test
    void theLuaScriptsDoTheCompareAndSetAndTheIncrementDelete() throws Exception {
        String put = new String(new org.springframework.core.io.ClassPathResource("redis/entitlement-put-if-generation.lua")
                .getInputStream().readAllBytes());
        String evict = new String(new org.springframework.core.io.ClassPathResource("redis/entitlement-evict.lua")
                .getInputStream().readAllBytes());
        org.assertj.core.api.Assertions.assertThat(put).contains("redis.call('GET', KEYS[2])").contains("current == ARGV[1]")
                .contains("redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])");
        org.assertj.core.api.Assertions.assertThat(evict).contains("redis.call('INCR', KEYS[i + 1])").contains("redis.call('DEL', KEYS[i])");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theMarkerWriteIsOneScriptThatBumpsTheGenerationAndSetsTheMarker() throws Exception {
        StringRedisTemplate t = mock(StringRedisTemplate.class);
        new RedisEntitlementStore(t).markEvicted(List.of("plans:v1:tenant:7"), "!evicted", Duration.ofSeconds(2));
        org.mockito.ArgumentCaptor<List<String>> keys = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(t).execute(org.mockito.ArgumentMatchers.any(org.springframework.data.redis.core.script.RedisScript.class), keys.capture(),
                org.mockito.ArgumentMatchers.eq("86400000"), org.mockito.ArgumentMatchers.eq("!evicted"), org.mockito.ArgumentMatchers.eq("2000"));
        org.assertj.core.api.Assertions.assertThat(keys.getValue()).containsExactly("plans:v1:tenant:7", "plans:v1:gen:tenant:7");
        String lua = new String(new org.springframework.core.io.ClassPathResource("redis/entitlement-mark-evicted.lua").getInputStream().readAllBytes());
        org.assertj.core.api.Assertions.assertThat(lua).contains("redis.call('INCR', KEYS[i + 1])").contains("redis.call('SET', KEYS[i], ARGV[2], 'PX', ARGV[3])");
    }

    @Test
    void theDedicatedClientHasShortTimeoutsAndRejectsCommandsWhileDisconnected() {
        io.lettuce.core.ClientOptions o = RedisEntitlementStore.clientOptions();
        org.assertj.core.api.Assertions.assertThat(o.getDisconnectedBehavior()).isEqualTo(io.lettuce.core.ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
        org.assertj.core.api.Assertions.assertThat(o.getSocketOptions().getConnectTimeout()).isLessThanOrEqualTo(Duration.ofMillis(500));
        org.assertj.core.api.Assertions.assertThat(o.getTimeoutOptions().isTimeoutCommands()).isTrue();
        org.assertj.core.api.Assertions.assertThat(RedisEntitlementStore.COMMAND_TIMEOUT).isLessThanOrEqualTo(Duration.ofMillis(500));
    }
}
