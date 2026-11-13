package com.example.perfume_budget.tenancy;

import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import com.example.perfume_budget.support.TestTenants;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TENANT-03 AC2 (production configuration): prod profile + the production default
 * {@code tenant.header-resolution.enabled=false}. The header is ignored, including when an Origin
 * resolves a real tenant (Origin wins) and when it names another tenant (no cross-tenant steering).
 */
@ActiveProfiles("prod")
@TestPropertySource(properties = {"tenant.base-domain=shopfi.test", "tenant.header-resolution.enabled=false"})
class TenantHeaderDisabledPropertyTest extends AbstractPostgresIntegrationTest {

    @Autowired TenantResolutionFilter filter;
    @Autowired TestTenants tenants;

    private int[] status = new int[1];

    private TenantIdentity bound(String origin, String header) throws Exception {
        tenants.acme();
        tenants.bravo();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/product");
        if (origin != null) req.addHeader("Origin", origin);
        if (header != null) req.addHeader("X-Tenant-ID", header);
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<TenantIdentity> bound = new AtomicReference<>();
        FilterChain chain = (rq, rs) -> bound.set(TenantContext.get());
        filter.doFilter(req, res, chain);
        status[0] = res.getStatus();
        return bound.get();
    }

    @Test
    void headerAloneDoesNotResolveATenant() throws Exception {
        assertThat(bound(null, "acme")).isNull();
        assertThat(bound(null, "bravo")).isNull();
    }

    @Test
    void headerCannotSteerAwayFromOriginTenant() throws Exception {
        assertThat(bound("https://acme.shopfi.test", "bravo").slug()).isEqualTo("acme");
    }

    @Test
    void headerDoesNotRescueAnUnknownOrigin() throws Exception {
        assertThat(bound("https://evil.example.com", "acme")).isNull();
        assertThat(status[0]).isEqualTo(404);
    }

    @Test
    void headerWithApexOriginIsRejected() throws Exception {
        assertThat(bound("http://localhost:3000", "acme")).isNull();
        assertThat(status[0]).isEqualTo(404);
    }
}
