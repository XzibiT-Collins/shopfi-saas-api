package com.example.perfume_budget.tenancy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DOMAIN-FD: the production domain is configuration. No platform domain literal may live in shipped code, templates,
 * property files, deployment descriptors or the API docs/quick start (they use placeholders or the env name). Test
 * fixtures are exempt: they are values passed INTO the configurable parts.
 */
class NoHardCodedDomainTest {

    static final Pattern FORBIDDEN = Pattern.compile(
            "shopfi\\.(app|store|com|io)|uniitflow|perfumebudget\\.com|vercel\\.app|shopfi-admin", Pattern.CASE_INSENSITIVE);

    private static final List<String> ROOTS = List.of("src/main", "docs", "QUICK_START.md", "render.yaml", "Dockerfile",
            "docker-compose.yml");

    @Test
    void noPlatformDomainLiteralOutsideTests() throws IOException {
        List<String> hits = new ArrayList<>();
        for (String root : ROOTS) {
            Path p = Paths.get(root);
            if (!Files.exists(p)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(p)) {
                for (Path f : files.filter(Files::isRegularFile).toList()) {
                    String name = f.getFileName().toString();
                    if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".ico") || name.endsWith(".class")) {
                        continue;
                    }
                    String text = Files.readString(f, StandardCharsets.UTF_8);
                    var m = FORBIDDEN.matcher(text);
                    if (m.find()) {
                        hits.add(f + " -> " + m.group());
                    }
                }
            }
        }
        assertThat(hits).as("hard-coded domain literals (use TENANT_BASE_DOMAIN & co. or a placeholder)").isEmpty();
    }

    @Test
    void theScannerFindsALiteralWhenOnePlanted() {
        assertThat(FORBIDDEN.matcher("see https://acme.shopfi.app/x").find()).isTrue();
        assertThat(FORBIDDEN.matcher("api.shop.uniitflow.com").find()).isTrue();
        assertThat(FORBIDDEN.matcher("https://acme.<TENANT_BASE_DOMAIN>").find()).isFalse();
    }
}
