package com.example.perfume_budget.security;

import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.repository.TenantRepository;
import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import com.example.perfume_budget.support.TestTenants;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEC-01f on a REAL Tomcat (MockMvc never makes the container's /error dispatch, so the suite could not see this):
 * every failure answered through {@code sendError} or an exception in a filter must reach the client with its own
 * status, not as the 401 the anonymous /error dispatch used to turn into.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "tenant.base-domain=shopfi.test",
        "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=0"
})
@Import(ErrorDispatchStatusIntegrationTest.ThrowingFilterConfig.class)
class ErrorDispatchStatusIntegrationTest extends AbstractPostgresIntegrationTest {

    static final String SECRET = "Key (email)=(errdispatch-leak@x.test) already exists";

    @TestConfiguration(proxyBeanMethods = false)
    static class ThrowingFilterConfig {
        @Bean
        FilterRegistrationBean<Filter> sec01fThrowingFilter() {
            Filter filter = (req, res, chain) -> {
                if (((HttpServletRequest) req).getRequestURI().endsWith("/boom")) {
                    throw new IllegalStateException(SECRET);
                }
                chain.doFilter(req, res);
            };
            FilterRegistrationBean<Filter> bean = new FilterRegistrationBean<>(filter);
            bean.addUrlPatterns("/__sec01f-probe/*");
            bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
            return bean;
        }
    }

    @LocalServerPort int port;
    @Autowired TestTenants tenants;
    @Autowired TenantRepository tenantRepository;

    /** Referer carries the tenant signal where Origin would be stopped earlier by the CORS filter (403, never reaching the tenant filter). */
    private HttpResponse<String> getWithReferer(String path, String referer) throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20))
                        .header("Referer", referer).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String origin) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).GET();
        if (origin != null) {
            b.header("Origin", origin);
        }
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                .send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aFilterExceptionIs500NotAnonymous401AndLeaksNothing() throws Exception {
        tenants.acme();
        HttpResponse<String> r = get("/__sec01f-probe/boom", "https://acme.shopfi.test");
        assertThat(r.statusCode()).isEqualTo(500);
        assertThat(r.body()).doesNotContain("errdispatch-leak@x.test");
    }

    @Test
    void noTenantSignalIs400() throws Exception {
        HttpResponse<String> r = get("/api/v1/product/listing", null);
        assertThat(r.statusCode()).isEqualTo(400);
    }

    @Test
    void anUnknownTenantOriginIs404() throws Exception {
        HttpResponse<String> r = getWithReferer("/api/v1/product/listing", "https://nosuchshop.shopfi.test/");
        assertThat(r.statusCode()).isEqualTo(404);
    }

    @Test
    void aSuspendedTenantIs423() throws Exception {
        tenants.ensureTenant("fixa-susp");
        setStatus("fixa-susp", TenantStatus.SUSPENDED);
        HttpResponse<String> r = getWithReferer("/api/v1/product/listing", "https://fixa-susp.shopfi.test/");
        assertThat(r.statusCode()).isEqualTo(423);
    }

    @Test
    void aGenuinelyUnauthenticatedProtectedCallIsStill401() throws Exception {
        tenants.acme();
        HttpResponse<String> r = get("/api/v1/auth/me", "https://acme.shopfi.test");
        assertThat(r.statusCode()).isEqualTo(401);
    }

    @Test
    void permittingTheErrorDispatchDoesNotOpenAnyPath() throws Exception {
        tenants.acme();
        // a protected path called directly (REQUEST dispatch) stays guarded
        assertThat(get("/api/v1/platform/tenants", "https://acme.shopfi.test").statusCode()).isIn(401, 403);
        // /error itself requested directly is a REQUEST dispatch: not an authenticated-only leak, and not a 500
        assertThat(get("/error", "https://acme.shopfi.test").statusCode()).isNotEqualTo(500);
    }

    private void setStatus(String slug, TenantStatus status) {
        Tenant t = tenantRepository.findBySlug(slug).orElseThrow();
        t.setStatus(status);
        tenantRepository.save(t);
    }
}
