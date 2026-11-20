package com.example.perfume_budget.tenancy;

import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.service.TenantRegistryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FIX-H (FIX-G review L5, DOMAIN-01c AC3 mutant R13): on a TENANT host the neutral bypass list is case-SENSITIVE, because
 * MVC and Security are, so {@code /ACTUATOR}, {@code /WS}, {@code /API/V1/WEBHOOK} are ordinary tenant paths: the tenant is
 * resolved and bound for them (never run neutral). Only the platform and OAuth2 zones compare lower-cased. Drives the real
 * {@link TenantResolutionFilter} (no database) and records the context bound when the chain runs.
 */
class TenantResolutionCaseVariantPathsTest {

    private static final String ORIGIN = "https://acme.shopfi.test";

    private final TenantResolutionFilter filter;

    TenantResolutionCaseVariantPathsTest() {
        TenantRegistryService registry = mock(TenantRegistryService.class);
        when(registry.findBySlug("acme")).thenReturn(Optional.of(Tenant.builder().slug("acme").displayName("Acme")
                .schemaName("tenant_acme").status(TenantStatus.ACTIVE).ownerEmail("o@acme.test").build()));
        when(registry.findBySlug("nobody")).thenReturn(Optional.empty());
        TenantResolutionProperties props = new TenantResolutionProperties();
        props.setBaseDomain("shopfi.test");
        filter = new TenantResolutionFilter(registry, props, new MockEnvironment());
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    /** The identity bound while the downstream chain runs, "none" when the filter let the request through neutral. */
    private String boundFor(String uri) throws Exception {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", uri);
        r.setRequestURI(uri);
        r.addHeader("Origin", ORIGIN);
        AtomicReference<String> seen = new AtomicReference<>("chain not reached");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(r, res, (rq, rs) -> {
            TenantIdentity id = TenantContext.get();
            seen.set(id == null ? "none" : id.scope() + ":" + id.slug());
        });
        assertThat(TenantContext.get()).as("context always cleared").isNull();
        return seen.get();
    }

    @Test
    void lowerCaseBypassPathsRunNeutralOnATenantHost() throws Exception {
        for (String uri : List.of("/actuator/health", "/ws/info", "/api/v1/webhook/abc", "/swagger-ui/index.html",
                "/v3/api-docs", "/error", "/login/oauth2/code/google")) {
            assertThat(boundFor(uri)).as(uri).isEqualTo("none");
        }
    }

    @Test
    void upperAndMixedCaseVariantsOfBypassPathsAreOrdinaryTenantPaths() throws Exception {
        for (String uri : List.of("/ACTUATOR/health", "/Actuator/health", "/aCtuator", "/WS", "/Ws/info",
                "/API/V1/WEBHOOK/abc", "/Api/V1/Webhook/abc", "/SWAGGER-UI/index.html", "/V3/API-DOCS", "/ERROR",
                "/LOGIN/OAUTH2/code/google", "/FAVICON.ICO")) {
            assertThat(boundFor(uri)).as(uri).isEqualTo("TENANT:acme");
        }
    }

    @Test
    void dotSegmentsAreResolvedBeforeTheCaseSensitiveBypassDecision() throws Exception {
        assertThat(boundFor("/x/../ACTUATOR/health")).isEqualTo("TENANT:acme");
        assertThat(boundFor("/./ACTUATOR/health")).isEqualTo("TENANT:acme");
        assertThat(boundFor("/x/../actuator/health")).isEqualTo("none");
        assertThat(boundFor("/./actuator/health")).isEqualTo("none");
        assertThat(boundFor("/actuator/../ACTUATOR/health")).isEqualTo("TENANT:acme");
    }

    @Test
    void platformAndOauth2ZonesStillCompareLowerCased() throws Exception {
        for (String uri : List.of("/API/V1/PLATFORM/tenants", "/Api/V1/Platform/auth/login")) {
            assertThat(boundFor(uri)).as(uri).isEqualTo("PLATFORM:__platform__");
        }
        for (String uri : List.of("/OAUTH2/authorization/google", "/OAuth2/authorization/google")) {
            assertThat(boundFor(uri)).as(uri).isEqualTo("none");
        }
    }

    @Test
    void anUpperCasedBypassPathOnAnUnknownTenantHostIsStillRefused() throws Exception {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/ACTUATOR/health");
        r.setRequestURI("/ACTUATOR/health");
        r.addHeader("Origin", "https://nobody.shopfi.test");
        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean[] reached = {false};
        filter.doFilter(r, res, (rq, rs) -> reached[0] = true);
        assertThat(res.getStatus()).isEqualTo(404);
        assertThat(reached[0]).isFalse();
    }
}
