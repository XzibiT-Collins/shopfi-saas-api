package com.example.perfume_budget.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.test.util.ReflectionTestUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** FIX-A2 (5): absorbed cache failures are counted per cache and operation, and a Prometheus rule alerts on them. */
class CacheErrorMetricTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();

    @SuppressWarnings("unchecked")
    private CacheErrorHandler handler(MeterRegistry r) {
        CacheErrorConfig config = new CacheErrorConfig();
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(r);
        ReflectionTestUtils.setField(config, "registry", provider);
        return config.errorHandler();
    }

    private double count(String cache, String op) {
        var c = registry.find(CacheErrorConfig.ERRORS_METRIC).tag("cache", cache).tag("op", op).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void everyAbsorbedOrRethrownFailureIsCountedByCacheAndOperation() {
        Cache cache = new ConcurrentMapCacheManager("products").getCache("products");
        CacheErrorHandler h = handler(registry);
        h.handleCacheGetError(new RuntimeException("x"), cache, "k");
        h.handleCacheGetError(new RuntimeException("x"), cache, "k");
        h.handleCachePutError(new RuntimeException("x"), cache, "k", "v");
        assertThatThrownBy(() -> h.handleCacheEvictError(new IllegalStateException("e"), cache, "k"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> h.handleCacheClearError(new IllegalStateException("e"), cache))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("products", "get")).isEqualTo(2);
        assertThat(count("products", "put")).isEqualTo(1);
        assertThat(count("products", "evict")).isEqualTo(1);
        assertThat(count("products", "clear")).isEqualTo(1);
    }

    @Test
    void aMissingOrBrokenRegistryNeverTurnsAMissIntoAFailure() {
        Cache cache = new ConcurrentMapCacheManager("products").getCache("products");
        handler(null).handleCacheGetError(new RuntimeException("x"), cache, "k");
        new CacheErrorConfig().errorHandler().handleCacheGetError(new RuntimeException("x"), cache, "k");
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> broken = mock(ObjectProvider.class);
        when(broken.getIfAvailable()).thenThrow(new IllegalStateException("registry down"));
        CacheErrorConfig config = new CacheErrorConfig();
        ReflectionTestUtils.setField(config, "registry", broken);
        config.errorHandler().handleCacheGetError(new RuntimeException("x"), cache, "k");
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePrometheusRuleWatchesTheMetricTheCodeEmitsAndIsLoadedByPrometheus() throws IOException {
        Map<String, Object> rules = new Yaml().load(Files.readString(Path.of("prometheus-alerts.yml")));
        List<Map<String, Object>> alerts = ((List<Map<String, Object>>) rules.get("groups")).stream()
                .flatMap(g -> ((List<Map<String, Object>>) g.get("rules")).stream()).toList();
        Map<String, Object> read = alerts.stream().filter(a -> "CacheReadErrors".equals(a.get("alert"))).findFirst().orElseThrow();
        String promName = CacheErrorConfig.ERRORS_METRIC.replace('.', '_') + "_total";
        assertThat((String) read.get("expr")).contains(promName).contains("op=\"get\"");
        assertThat(Files.readString(Path.of("prometheus.yml"))).contains("prometheus-alerts.yml");
        assertThat(Files.readString(Path.of("docker-compose.yml"))).contains("prometheus-alerts.yml");
    }
}
