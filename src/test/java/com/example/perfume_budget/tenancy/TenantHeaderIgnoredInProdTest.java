package com.example.perfume_budget.tenancy;

import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import com.example.perfume_budget.support.TestTenants;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TENANT-03 AC2: with the {@code prod} profile active the X-Tenant-ID header must not select a tenant.
 *
 * <p>NOTE for reviewers: the shared harness profile {@code pgtest} sets
 * {@code tenant.header-resolution.enabled=true} (needed by other tests). There is no
 * {@code application-prod.properties}, and {@link TenantResolutionFilter} never inspects the active
 * profile, so the only thing that disables the header in production is the property default (false).
 * {@link #prodProfileAlone_headerMustBeIgnored} asserts the AC literally (prod profile active =&gt; ignored).
 */
@ActiveProfiles("prod")
class TenantHeaderIgnoredInProdTest extends AbstractPostgresIntegrationTest {

    @Autowired TenantResolutionFilter filter;
    @Autowired TestTenants tenants;
    @Autowired Environment env;

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
        return bound.get();
    }

    @Test
    void prodProfileIsActive() {
        assertThat(Arrays.asList(env.getActiveProfiles())).contains("prod");
    }

    @Test
    void prodProfileAlone_headerMustBeIgnored() throws Exception {
        assertThat(bound(null, "acme"))
                .as("X-Tenant-ID must not resolve a tenant when the prod profile is active")
                .isNull();
    }
}
