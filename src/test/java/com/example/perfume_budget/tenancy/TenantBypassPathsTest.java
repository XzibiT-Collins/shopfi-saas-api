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
}
