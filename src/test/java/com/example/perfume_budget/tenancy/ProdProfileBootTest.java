package com.example.perfume_budget.tenancy;

import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import com.example.perfume_budget.support.PostgresTestDatabase;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TENANT-11 AC1: boots the REAL context under ONLY the {@code prod} profile (no test/pgtest layers, so
 * everything the app needs must come from application-prod.properties + environment-style values supplied
 * here under the exact env var names the profile reads). Proves prod boots and header resolution is off.
 */
@ActiveProfiles(profiles = "prod", inheritProfiles = false)
@TestPropertySource(properties = {
        "JWT_SECRET=dGVzdC1zZWNyZXQta2V5LXRlc3Qtc2VjcmV0LWtleS10ZXN0LXNlY3JldC1rZXk=",
        "JWT_ACCESS_EXPIRATION=3600000",
        "JWT_REFRESH_EXPIRATION=86400000",
        "SPRING_MAIL_HOST=smtp.invalid",
        "SPRING_MAIL_PORT=587",
        "SPRING_MAIL_USERNAME=mailer@shopfi.test",
        "SPRING_MAIL_PASSWORD=not-a-real-password",
        "SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true",
        "SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_ENABLE=false",
        "SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true",
        "SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_TRUST=smtp.invalid",
        "SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_PROTOCOLS=TLSv1.2",
        "MANAGEMENT_HEALTH_MAIL_ENABLED=false",
        "REDIS_URL=redis://localhost:6379",
        "GOOGLE_CLIENT_ID=test-client-id",
        "GOOGLE_CLIENT_SECRET=test-client-secret",
        "OAUTH2_CALLBACK_BASE_URL=https://api.shopfi.test",
        "OAUTH2_REDIRECT_URIS=https://acme.shopfi.test/oauth2/redirect",
        "PLATFORM_ENCRYPTION_KEY=test-encryption-key",
        "PLATFORM_API_BASE_URL=https://api.shopfi.test",
        "CLOUDINARY_CLOUD_NAME=test",
        "CLOUDINARY_API_KEY=test",
        "CLOUDINARY_API_SECRET=test",
        "ADMIN_EMAIL=admin@shopfi.test",
        "ADMIN_PASSWORD=test-admin-password",
        "PLATFORM_ADMIN_EMAIL=platform-root@shopfi.test",
        "PLATFORM_ADMIN_PASSWORD=Pl4tform-boot-pass!",
        "FRONTEND_CHANGE_PASSWORD_URL=https://app.shopfi.test/reset-password",
        "CORS_ALLOWED_ORIGINS=https://app.shopfi.test",
        "TENANT_PLATFORM_DOMAINS=api.shopfi.test",
        "TENANT_BASE_DOMAIN=shopfi.test",
        "spring.datasource.hikari.maximum-pool-size=3"
})
class ProdProfileBootTest extends AbstractPostgresIntegrationTest {

    @Autowired Environment env;
    @Autowired ApplicationContext ctx;
    @Autowired TenantResolutionProperties props;
    @Autowired TenantResolutionFilter filter;

    @DynamicPropertySource
    static void prodEnvDatabase(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", () -> PostgresTestDatabase.URL);
        registry.add("DB_USER", () -> PostgresTestDatabase.USER);
        registry.add("DB_PASSWORD", () -> PostgresTestDatabase.PASSWORD);
    }

    @Test
    void onlyProdProfileIsActiveAndContextBooted() {
        assertThat(Arrays.asList(env.getActiveProfiles())).containsExactly("prod");
        assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(env.getProperty("spring.datasource.hikari.connection-init-sql"))
                .isEqualTo("SET search_path TO tenant_template");
        assertThat(PostgresTestDatabase.tableExists("master", "tenants")).isTrue();
    }

    @Test
    void headerResolutionAndDevSettingsAreOff() {
        assertThat(props.getHeaderResolution().isEnabled()).isFalse();
        assertThat(props.getOauth2().getDevAllowedRedirectHosts()).isEmpty();
        assertThat(props.getBaseDomain()).isEqualTo("shopfi.test");
        assertThat(props.getPlatformDomains()).containsExactly("api.shopfi.test");
        assertThat(env.getProperty("tenant.dev-endpoints.enabled")).isEqualTo("false");
        assertThat(ctx.getBeansOfType(com.example.perfume_budget.controller.DevTenantController.class)).isEmpty();
        assertThat(ctx.getBeansOfType(TenantHeaderResolutionGuard.class)).hasSize(1);
    }

    @Test
    void xTenantIdHeaderDoesNotResolveATenantUnderProd() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/product");
        req.addHeader("X-Tenant-ID", "acme");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<TenantIdentity> bound = new AtomicReference<>();
        FilterChain chain = (rq, rs) -> bound.set(TenantContext.get());
        filter.doFilter(req, res, chain);
        assertThat(bound.get()).isNull();
    }
}
