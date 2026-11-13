package com.example.perfume_budget.tenancy;

import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.repository.TenantRepository;
import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import com.example.perfume_budget.support.TestTenants;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TENANT-03 AC1 + AC5(423): drives the real {@link TenantResolutionFilter} (real registry on the real
 * master schema) and records the {@link TenantContext} that is bound when the downstream chain runs.
 * Precedence: customDomain > subdomain > dev X-Tenant-ID > apex/platform > unknown origin (404).
 */
@TestPropertySource(properties = {
        "tenant.base-domain=shopfi.test",
        "tenant.header-resolution.enabled=true"
})
class TenantResolutionPrecedenceTest extends AbstractPostgresIntegrationTest {

    static final String CUSTOM_DOMAIN = "shop.cdom-brand.test";
    /** Deliberately equals zeta's subdomain host, so the same Origin matches BOTH a custom domain and a subdomain. */
    static final String COLLIDING_HOST = "zeta.shopfi.test";

    @Autowired TenantResolutionFilter filter;
    @Autowired TenantRepository tenantRepository;
    @Autowired TestTenants tenants;

    record Outcome(int status, String errorMessage, TenantIdentity bound, boolean chainInvoked) {
        String slug() {
            return bound == null ? null : bound.slug();
        }
    }


    private void fixtures() {
        tenants.acme();
        tenants.bravo();
        tenants.ensureTenant("cdom");
        tenants.ensureTenant("susp");
        tenants.ensureTenant("pend");
        Tenant cdom = tenantRepository.findBySlug("cdom").orElseThrow();
        cdom.setCustomDomain(CUSTOM_DOMAIN);
        tenantRepository.save(cdom);
        // second tenant owning a custom domain equal to bravo's subdomain host
        tenants.ensureTenant("zeta");
        tenants.ensureTenant("collide");
        Tenant collide = tenantRepository.findBySlug("collide").orElseThrow();
        collide.setCustomDomain(COLLIDING_HOST);
        tenantRepository.save(collide);
        setStatus("susp", TenantStatus.SUSPENDED);
        setStatus("pend", TenantStatus.PROVISIONING);
    }

    private void setStatus(String slug, TenantStatus status) {
        Tenant t = tenantRepository.findBySlug(slug).orElseThrow();
        t.setStatus(status);
        tenantRepository.save(t);
    }

    private Outcome run(String path, String origin, String referer, String headerTenant) throws Exception {
        fixtures();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        if (origin != null) req.addHeader("Origin", origin);
        if (referer != null) req.addHeader("Referer", referer);
        if (headerTenant != null) req.addHeader("X-Tenant-ID", headerTenant);
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<TenantIdentity> bound = new AtomicReference<>();
        AtomicReference<Boolean> invoked = new AtomicReference<>(false);
        FilterChain chain = (rq, rs) -> {
            invoked.set(true);
            bound.set(TenantContext.get());
        };
        filter.doFilter(req, res, chain);
        assertThat(TenantContext.get()).as("context must always be cleared in finally").isNull();
        return new Outcome(res.getStatus(), res.getErrorMessage(), bound.get(), invoked.get());
    }

    private static final String TENANT_PATH = "/api/v1/product";

    @Test
    void ac1_customDomain_resolvesTenantOwningThatDomain() throws Exception {
        Outcome o = run(TENANT_PATH, "https://" + CUSTOM_DOMAIN, null, null);
        assertThat(o.chainInvoked()).isTrue();
        assertThat(o.slug()).isEqualTo("cdom");
        assertThat(o.bound().schemaName()).isEqualTo("tenant_cdom");
    }

    @Test
    void ac1_customDomain_beatsSubdomain_whenOriginMatchesBoth() throws Exception {
        // zeta.shopfi.test is zeta's subdomain AND tenant "collide"'s custom domain -> custom domain wins.
        Outcome o = run(TENANT_PATH, "https://" + COLLIDING_HOST, null, null);
        assertThat(o.slug()).isEqualTo("collide");
    }

    @Test
    void ac1_subdomain_resolvesBySlug_andOriginPortAndCaseAreNormalised() throws Exception {
        assertThat(run(TENANT_PATH, "https://acme.shopfi.test", null, null).slug()).isEqualTo("acme");
        assertThat(run(TENANT_PATH, "https://ACME.ShopFi.Test:8443", null, null).slug()).isEqualTo("acme");
    }

    @Test
    void ac1_subdomain_beatsDevHeader() throws Exception {
        Outcome o = run(TENANT_PATH, "https://acme.shopfi.test", null, "bravo");
        assertThat(o.slug()).isEqualTo("acme");
    }

    @Test
    void ac1_customDomain_beatsDevHeader() throws Exception {
        Outcome o = run(TENANT_PATH, "https://" + CUSTOM_DOMAIN, null, "acme");
        assertThat(o.slug()).isEqualTo("cdom");
    }

    @Test
    void ac1_refererIsUsedWhenOriginIsAbsent() throws Exception {
        Outcome o = run(TENANT_PATH, null, "https://acme.shopfi.test/some/page", null);
        assertThat(o.slug()).isEqualTo("acme");
    }

    @Test
    void ac1_devHeader_resolvesWhenNoOriginAndHeaderEnabled() throws Exception {
        Outcome o = run(TENANT_PATH, null, null, "Bravo");
        assertThat(o.slug()).isEqualTo("bravo");
    }

    @Test
    void ac1_devHeader_withApexOrigin_resolvesHeaderTenant() throws Exception {
        // localhost is a platform domain: it never matches a tenant host, so the header is next in line.
        Outcome o = run(TENANT_PATH, "http://localhost:3000", null, "acme");
        assertThat(o.slug()).isEqualTo("acme");
    }

    @Test
    void ac1_devHeader_unknownSlug_isNotResolved_andFallsThroughNeutral() throws Exception {
        Outcome o = run(TENANT_PATH, null, null, "does-not-exist");
        assertThat(o.chainInvoked()).isTrue();
        assertThat(o.bound()).isNull();
    }

    @Test
    void ac1_apexPlatform_platformPathIsPlatformScoped_regardlessOfOriginOrHeader() throws Exception {
        for (String origin : new String[]{"http://localhost:3000", "http://127.0.0.1", null, "https://acme.shopfi.test"}) {
            Outcome o = run("/api/v1/platform/tenants", origin, null, "bravo");
            assertThat(o.bound()).as("origin=%s", origin).isNotNull();
            assertThat(o.bound().scope()).isEqualTo(TenantScope.PLATFORM);
            assertThat(o.slug()).isEqualTo(TenantContext.PLATFORM_SLUG);
        }
    }

    @Test
    void ac1_unknownOrigin_is404_andChainNeverRuns() throws Exception {
        for (String origin : new String[]{"https://nobody.shopfi.test", "https://evil.example.com", "https://shopfi.test"}) {
            Outcome o = run(TENANT_PATH, origin, null, null);
            assertThat(o.status()).as("origin=%s", origin).isEqualTo(404);
            assertThat(o.chainInvoked()).as("origin=%s", origin).isFalse();
        }
    }

    @Test
    void ac1_unknownOrigin_is404_evenWhenDevHeaderNamesAValidTenant_onlyIfNoOriginMatch() throws Exception {
        // Unknown non-platform origin + valid header: header is still consulted (non-prod) -> resolves header tenant.
        // This pins current behaviour: header only loses to a *matching* domain/subdomain.
        Outcome o = run(TENANT_PATH, "https://evil.example.com", null, "acme");
        assertThat(o.status()).isEqualTo(200);
        assertThat(o.slug()).isEqualTo("acme");
    }

    @Test
    void ac1_apexOriginOnTenantPathWithoutHeader_isRejected404() throws Exception {
        Outcome o = run(TENANT_PATH, "http://localhost:3000", null, null);
        assertThat(o.status()).isEqualTo(404);
        assertThat(o.chainInvoked()).isFalse();
    }

    @Test
    void ac1_malformedOrigin_isTreatedAsNoSignal() throws Exception {
        Outcome o = run(TENANT_PATH, "not a url", null, null);
        assertThat(o.status()).isNotEqualTo(500);
    }

    @Test
    void ac5_suspendedTenant_returns423_bySubdomainAndByDevHeader_andChainNeverRuns() throws Exception {
        Outcome bySub = run(TENANT_PATH, "https://susp.shopfi.test", null, null);
        assertThat(bySub.status()).isEqualTo(423);
        assertThat(bySub.chainInvoked()).isFalse();
        Outcome byHeader = run(TENANT_PATH, null, null, "susp");
        assertThat(byHeader.status()).isEqualTo(423);
        assertThat(byHeader.chainInvoked()).isFalse();
    }

    @Test
    void ac5_nonActiveNonSuspendedTenant_is404() throws Exception {
        Outcome o = run(TENANT_PATH, "https://pend.shopfi.test", null, null);
        assertThat(o.status()).isEqualTo(404);
        assertThat(o.chainInvoked()).isFalse();
    }
}
