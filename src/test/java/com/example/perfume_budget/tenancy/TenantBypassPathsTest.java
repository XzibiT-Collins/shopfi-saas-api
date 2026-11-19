package com.example.perfume_budget.tenancy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** TENANT-11 AC2 (N1): /test/ws must never be a tenant-bypass (neutral) path. Catches BYPASS_PREFIXES regressions. */
class TenantBypassPathsTest {

    @Test
    void testWsIsNotABypassPath() {
        assertThat(TenantResolutionFilter.isNeutralPath("/test/ws")).isFalse();
        assertThat(TenantResolutionFilter.isNeutralPath("/test")).isFalse();
        assertThat(TenantResolutionFilter.isNeutralPath("/test/anything/else")).isFalse();
    }

    @Test
    void knownNeutralPathsStillBypass() {
        assertThat(TenantResolutionFilter.isNeutralPath("/actuator/health")).isTrue();
        assertThat(TenantResolutionFilter.isNeutralPath("/api/v1/webhook/abc")).isTrue();
    }

    /** DOMAIN-01c AC3: the bypass list is case-sensitive (MVC/Security are); only the platform/oauth2 zones compare lower-cased. */
    @Test
    void bypassListIsCaseSensitiveButPlatformAndOauthZonesAreNot() {
        assertThat(TenantResolutionFilter.isNeutralPath("/ACTUATOR/health")).isFalse();
        assertThat(TenantResolutionFilter.isNeutralPath("/WS")).isFalse();
        assertThat(TenantResolutionFilter.isNeutralPath("/API/V1/WEBHOOK/abc")).isFalse();
        assertThat(TenantResolutionFilter.isNeutralPath("/API/V1/PLATFORM/tenants")).isTrue();
    }

    @Test
    void theRequiredFilterRejectsUpperCasedBypassLookalikes() throws Exception {
        TenantContext.clear();
        var filter = new TenantRequiredFilter();
        for (String uri : new String[]{"/ACTUATOR/health", "/WS", "/API/V1/WEBHOOK/x"}) {
            var res = new org.springframework.mock.web.MockHttpServletResponse();
            var chain = new org.springframework.mock.web.MockFilterChain();
            var req = new org.springframework.mock.web.MockHttpServletRequest("GET", uri);
            filter.doFilter(req, res, chain);
            assertThat(res.getStatus()).as(uri).isEqualTo(400);
            assertThat(chain.getRequest()).as(uri + " must not reach the chain").isNull();
        }
        var res = new org.springframework.mock.web.MockHttpServletResponse();
        var chain = new org.springframework.mock.web.MockFilterChain();
        filter.doFilter(new org.springframework.mock.web.MockHttpServletRequest("GET", "/actuator/health"), res, chain);
        assertThat(chain.getRequest()).isNotNull();
    }
}
