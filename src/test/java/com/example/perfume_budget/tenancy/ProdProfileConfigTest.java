package com.example.perfume_budget.tenancy;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** TENANT-11 AC1: application-prod.properties carries no secret values and no insecure defaults. */
class ProdProfileConfigTest {

    private static final Pattern PURE_ENV = Pattern.compile("^\\$\\{[A-Z0-9_]+}$");

    private Properties prod() throws Exception {
        Properties p = new Properties();
        try (InputStream in = new ClassPathResource("application-prod.properties").getInputStream()) {
            p.load(in);
        }
        return p;
    }

    @Test
    void secretsAndUrlsAreBareEnvPlaceholdersWithoutDefaults() throws Exception {
        Properties p = prod();
        String[] mustBeBareEnv = {
                "spring.datasource.url", "spring.datasource.username", "spring.datasource.password",
                "application.security.jwt.secret-key", "spring.mail.password", "spring.mail.username",
                "spring.data.redis.url", "spring.security.oauth2.client.registration.google.client-id",
                "spring.security.oauth2.client.registration.google.client-secret",
                "platform.encryption.key", "platform.api-base-url", "storage.cloudinary.api-key",
                "storage.cloudinary.api-secret", "storage.cloudinary.cloud-name",
                "perfume.budget.admin.password", "perfume.budget.admin.email",
                "frontend.change-password-url", "cors.allowed-origins", "tenant.base-domain",
                "tenant.platform-domains", "application.oauth2.authorized-redirect-uris"
        };
        for (String k : mustBeBareEnv) {
            assertThat(p.getProperty(k)).as(k).matches(PURE_ENV);
        }
    }

    @Test
    void noLocalhostOrDevValuesAnywhere() throws Exception {
        Properties p = prod();
        for (String k : p.stringPropertyNames()) {
            if (k.equals("tenant.oauth2.dev-allowed-redirect-hosts")) {
                assertThat(p.getProperty(k)).isEmpty();
            } else {
                assertThat(p.getProperty(k)).as(k).doesNotContain("localhost").doesNotContain("127.0.0.1")
                        .doesNotContain("dev-insecure");
            }
        }
        assertThat(p.getProperty("tenant.header-resolution.enabled")).isEqualTo("false");
        assertThat(p.getProperty("tenant.dev-endpoints.enabled")).isEqualTo("false");
        assertThat(p.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(p.getProperty("spring.flyway.enabled")).isEqualTo("false");
        assertThat(p.getProperty("application.security.cookie.secure")).isEqualTo("true");
    }
}
