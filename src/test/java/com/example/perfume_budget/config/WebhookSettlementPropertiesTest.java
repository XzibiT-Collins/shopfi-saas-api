package com.example.perfume_budget.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EVT-01d (3): the production webhook settlement limits live in application.properties as
 * {@code ${ENV_VAR:default}}. The test profile raises the permits to 64 for its race tests, so nothing else would notice
 * a changed production default (reviewer mutant N: 3 -> 10 survived). This pins each default as it resolves with the
 * environment variable unset, and checks that no other main profile file (dev, prod) sets the keys again.
 */
class WebhookSettlementPropertiesTest {

    static final Map<String, String> PRODUCTION_DEFAULTS = Map.of(
            "shopfi.payments.webhook.max-concurrent-settlements", "3",
            "shopfi.payments.webhook.max-concurrent-settlements-per-tenant", "1",
            "shopfi.payments.webhook.permit-wait-ms", "50",
            "shopfi.webhook.lock-timeout-ms", "8000",
            "shopfi.webhook.settlement-deadline-ms", "20000");

    private static Properties load(Path p) throws IOException {
        Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(p)) {
            props.load(r);
        }
        return props;
    }

    @Test
    void theBasePropertiesResolveToTheProductionDefaults_withTheEnvironmentVariablesUnset() throws IOException {
        Properties base = load(Path.of("src/main/resources/application.properties"));
        // only the file itself: no system environment, so a deployment's variables cannot mask a changed default
        StandardEnvironment env = new StandardEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        sources.forEach(s -> sources.remove(s.getName()));
        sources.addFirst(new PropertiesPropertySource("application.properties", base));
        PRODUCTION_DEFAULTS.forEach((key, value) -> {
            assertThat(base.getProperty(key)).as(key + " is set in application.properties").isNotNull();
            assertThat(env.getProperty(key)).as(key).isEqualTo(value);
        });
    }

    @Test
    void theEnvironmentVariablesStillOverrideThem() throws IOException {
        Properties base = load(Path.of("src/main/resources/application.properties"));
        assertThat(base.getProperty("shopfi.payments.webhook.max-concurrent-settlements"))
                .isEqualTo("${SHOPFI_WEBHOOK_MAX_CONCURRENT_SETTLEMENTS:3}");
        assertThat(base.getProperty("shopfi.payments.webhook.max-concurrent-settlements-per-tenant"))
                .isEqualTo("${SHOPFI_WEBHOOK_MAX_CONCURRENT_SETTLEMENTS_PER_TENANT:1}");
    }

    @Test
    void noOtherMainProfileFileSetsThem() throws IOException {
        List<String> overrides = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of("src/main/resources"))) {
            for (Path p : files.filter(f -> f.getFileName().toString().matches("application-.*\\.(properties|ya?ml)")).toList()) {
                Properties props = load(p);
                PRODUCTION_DEFAULTS.keySet().stream().filter(props::containsKey).forEach(k -> overrides.add(p + ": " + k));
            }
        }
        assertThat(overrides).isEmpty();
    }
}
