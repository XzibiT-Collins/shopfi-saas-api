package com.example.perfume_budget.config;

import org.junit.jupiter.api.Test;

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
 * SEC-01d (1, 2): the base application.properties (inherited by every profile) silences Hibernate's
 * SqlExceptionHelper (it logs the Postgres error text, {@code Detail: Key (email)=(..)} included, at ERROR) and pins
 * Spring Boot's /error body to never carry the exception message, binding errors, the exception class or a stack
 * trace. No profile file (dev, prod, test, pgtest) may set any of these keys again, and no logback file may exist
 * that could re-enable the logger outside Spring's properties.
 */
class ErrorExposurePropertiesTest {

    static final Map<String, String> PINNED = Map.of(
            "logging.level.org.hibernate.engine.jdbc.spi.SqlExceptionHelper", "OFF",
            "server.error.include-message", "never",
            "server.error.include-binding-errors", "never",
            "server.error.include-stacktrace", "never",
            "server.error.include-exception", "false");

    private static Properties load(Path p) throws IOException {
        Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(p)) {
            props.load(r);
        }
        return props;
    }

    @Test
    void theBasePropertiesPinEveryKey() throws IOException {
        Properties base = load(Path.of("src/main/resources/application.properties"));
        PINNED.forEach((key, value) -> assertThat(base.getProperty(key)).as(key).isEqualTo(value));
    }

    @Test
    void noProfileOrTestFileOverridesThem() throws IOException {
        List<String> overrides = new ArrayList<>();
        try (Stream<Path> files = Stream.concat(Files.list(Path.of("src/main/resources")), Files.list(Path.of("src/test/resources")))) {
            for (Path p : files.filter(f -> f.getFileName().toString().matches("application.*\\.(properties|ya?ml)")).toList()) {
                if (p.equals(Path.of("src/main/resources/application.properties"))) {
                    continue;
                }
                String text = Files.readString(p);
                for (String line : text.lines().map(String::strip).filter(l -> !l.startsWith("#")).toList()) {
                    String key = line.split("[=:]", 2)[0].strip().toLowerCase();
                    if (key.startsWith("server.error") || key.startsWith("logging.level.org.hibernate")
                            || key.equals("logging.level.root") || key.startsWith("logging.config")) {
                        overrides.add(p + ": " + key);
                    }
                }
            }
        }
        assertThat(overrides).isEmpty();
    }

    @Test
    void noLogbackFileCanReenableTheLogger() throws IOException {
        for (String dir : List.of("src/main/resources", "src/test/resources")) {
            try (Stream<Path> files = Files.walk(Path.of(dir))) {
                assertThat(files.map(f -> f.getFileName().toString()).filter(n -> n.startsWith("logback") || n.startsWith("log4j2")))
                        .as(dir).isEmpty();
            }
        }
    }
}
