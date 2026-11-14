package com.example.perfume_budget.platform.auth;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** PO addition (d): env names only, no defaults / values in the repo. */
class PlatformConfigTest {

    private static final Pattern PURE_ENV = Pattern.compile("^\\$\\{[A-Z0-9_]+}$");

    private Properties load(String name) throws Exception {
        Properties p = new Properties();
        try (InputStream in = new ClassPathResource(name).getInputStream()) {
            p.load(in);
        }
        return p;
    }

    @Test
    void prodHasBareEnvPlaceholdersWithNoDefaultForPlatformAdminCredentials() throws Exception {
        Properties prod = load("application-prod.properties");
        assertThat(prod.getProperty("platform.admin.email")).isEqualTo("${PLATFORM_ADMIN_EMAIL}").matches(PURE_ENV);
        assertThat(prod.getProperty("platform.admin.password")).isEqualTo("${PLATFORM_ADMIN_PASSWORD}").matches(PURE_ENV);
    }

    @Test
    void devAndBaseConfig_neverCarryANonEmptyPlatformAdminPassword() throws Exception {
        for (String f : new String[]{"application.properties", "application-dev.properties", "application-prod.properties"}) {
            Properties p = load(f);
            for (String k : p.stringPropertyNames()) {
                if (k.startsWith("platform.admin.password") || k.startsWith("platform.admin.email")) {
                    assertThat(p.getProperty(k)).as(f + " " + k).matches("^\\$\\{PLATFORM_ADMIN_(EMAIL|PASSWORD)(:)?}$");
                }
            }
        }
    }

    @Test
    void renderYamlListsTheNamesOnly_syncFalse() throws Exception {
        String yaml = Files.readString(Path.of("render.yaml"));
        for (String name : new String[]{"PLATFORM_ADMIN_EMAIL", "PLATFORM_ADMIN_PASSWORD"}) {
            assertThat(yaml).containsPattern("- key: " + name + "\\R\\s+sync: false");
        }
    }

    @Test
    void quickStartDocumentsTheVariables() throws Exception {
        String doc = Files.readString(Path.of("QUICK_START.md"));
        assertThat(doc).contains("PLATFORM_ADMIN_EMAIL").contains("PLATFORM_ADMIN_PASSWORD");
    }
}
