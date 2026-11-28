package com.example.perfume_budget.entitlement;

import com.example.perfume_budget.platform.enums.SubscriptionStatus;
import com.example.perfume_budget.platform.plan.dto.EntitlementView;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BILL-01c QA M1: the production store + cache against a REAL Redis that goes away. The test starts its OWN throwaway
 * {@code redis-server} on a private free port (never 6379, nothing shared is touched), kills it, and measures: reads fall
 * back to the database within the command timeout, an eviction returns within its wall-time bound, nothing is granted on an
 * error, and after the server is back the cache works again. The shared client the application would otherwise use has the
 * library defaults (60 s per command); the store must not inherit them.
 */
class EntitlementRedisOutageTest {

    static Process redis;
    static int port;
    static Path dir;
    static LettuceConnectionFactory shared;
    static RedisEntitlementStore store;
    static final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @SuppressWarnings("unchecked")
    static EntitlementCache cache() {
        ObjectProvider<MeterRegistry> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(meters);
        return new EntitlementCache(store, p);
    }

    static void startRedis() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("redis-server", "--port", Integer.toString(port), "--bind", "127.0.0.1",
                "--save", "", "--appendonly", "no", "--dir", dir.toString());
        pb.redirectErrorStream(true).redirectOutput(dir.resolve("redis.log").toFile());
        redis = pb.start();
        for (int i = 0; i < 100; i++) {
            try (Socket s = new Socket("127.0.0.1", port)) {
                return;
            } catch (Exception e) {
                Thread.sleep(50);
            }
        }
        throw new IllegalStateException("redis did not start");
    }

    static void stopRedis() throws Exception {
        if (redis != null) {
            redis.destroyForcibly();
            redis.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @BeforeAll
    static void up() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        assertThat(port).isNotEqualTo(6379);
        dir = Files.createTempDirectory("bill01c-redis");
        startRedis();
        shared = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", port)); // library defaults
        shared.afterPropertiesSet();
        store = new RedisEntitlementStore(shared);
    }

    @AfterAll
    static void down() throws Exception {
        store.close();
        shared.destroy();
        stopRedis();
    }

    private static TenantPlanState state(long plan) {
        return new TenantPlanState(5, plan, SubscriptionStatus.ACTIVE, null);
    }

    private static long ms(Runnable r) {
        long t0 = System.nanoTime();
        r.run();
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
    }

    @Test
    void redisGoesAway_readsFallBackToTheDatabaseFast_evictionIsBounded_nothingGrants_andItRecovers() throws Exception {
        EntitlementCache cache = cache();
        // healthy: cached
        assertThat(cache.tenant(5, () -> Optional.of(state(1))).orElseThrow().planId()).isEqualTo(1);
        assertThat(cache.tenant(5, () -> Optional.of(state(2))).orElseThrow().planId()).as("served from Redis").isEqualTo(1);

        stopRedis();

        // reads: the database answers, never a default and never the cached grant; each lookup within ~1 s (was ~120 s)
        for (int i = 0; i < 3; i++) {
            Optional<TenantPlanState>[] out = new Optional[1];
            long took = ms(() -> out[0] = cache.tenant(5, () -> Optional.of(state(3))));
            assertThat(out[0].orElseThrow().planId()).isEqualTo(3);
            assertThat(took).as("read %d during the outage", i).isLessThan(1200);
        }
        List<EntitlementView>[] plan = new List[1];
        long planMs = ms(() -> plan[0] = cache.plan(2, () -> List.of(new EntitlementView("WALK_IN_POS", false, null))));
        assertThat(plan[0]).containsExactly(new EntitlementView("WALK_IN_POS", false, null));
        assertThat(planMs).isLessThan(1200);
        assertThat(ms(() -> assertThat(cache.tenant(404, Optional::empty)).isEmpty())).isLessThan(1200);

        // eviction: returns, loudly, within its wall-time budget plus one command timeout (was ~300 s)
        double before = meters.counter(EntitlementCache.EVICTION_FAILED_METRIC).count();
        long evictMs = ms(() -> cache.evictTenant(5));
        assertThat(evictMs).as("eviction during the outage").isLessThan(3000);
        assertThat(meters.counter(EntitlementCache.EVICTION_FAILED_METRIC).count()).isEqualTo(before + 1);

        startRedis();

        // recovery: the client reconnects by itself; the cache caches again and an eviction really evicts
        long deadline = System.currentTimeMillis() + 15_000;
        String cached = null;
        while (cached == null && System.currentTimeMillis() < deadline) {
            cache.tenant(5, () -> Optional.of(state(4)));
            cached = store.get(EntitlementCache.tenantKey(5));
            if (cached == null) {
                Thread.sleep(200);
            }
        }
        assertThat(cached).as("the cache works again after the restart").contains("\"planId\":4");
        cache.evictTenant(5);
        assertThat(store.get(EntitlementCache.tenantKey(5))).isNull();
        assertThat(cache.tenant(5, () -> Optional.of(state(6))).orElseThrow().planId()).isEqualTo(6);
    }

    @Test
    void anUnreachableRedis_neverStartedOnThatPort_isFailFast() throws Exception {
        int dead;
        try (ServerSocket s = new ServerSocket(0)) {
            dead = s.getLocalPort(); // nothing listens once the socket is closed
        }
        LettuceConnectionFactory sh = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", dead));
        sh.afterPropertiesSet();
        RedisEntitlementStore st = new RedisEntitlementStore(sh);
        try {
            @SuppressWarnings("unchecked")
            ObjectProvider<MeterRegistry> p = mock(ObjectProvider.class);
            EntitlementCache c = new EntitlementCache(st, p);
            assertThat(ms(() -> assertThat(c.tenant(1, () -> Optional.of(state(9))).orElseThrow().planId()).isEqualTo(9))).isLessThan(1200);
            assertThat(ms(() -> c.evictTenant(1))).isLessThan(3000);
        } finally {
            st.close();
            sh.destroy();
        }
    }
}
