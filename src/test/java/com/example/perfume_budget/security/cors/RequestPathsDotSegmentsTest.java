package com.example.perfume_budget.security.cors;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** DOMAIN-01c AC3: dot segments are resolved by the canonical path helper (not only refused by the firewall). */
class RequestPathsDotSegmentsTest {

    @Test
    void dotSegmentsAreResolved() {
        assertThat(RequestPaths.removeDotSegments("/api/v1/./platform/x")).isEqualTo("/api/v1/platform/x");
        assertThat(RequestPaths.removeDotSegments("/api/v1/foo/../platform/x")).isEqualTo("/api/v1/platform/x");
        assertThat(RequestPaths.removeDotSegments("/../../api")).isEqualTo("/api");
        assertThat(RequestPaths.removeDotSegments("/a/b/..")).isEqualTo("/a/");
        assertThat(RequestPaths.removeDotSegments("/a/b/.")).isEqualTo("/a/b/");
        assertThat(RequestPaths.removeDotSegments("/plain/path")).isEqualTo("/plain/path");
        assertThat(RequestPaths.removeDotSegments("/a/.hidden/b")).isEqualTo("/a/.hidden/b");
        assertThat(RequestPaths.removeDotSegments("/a/..b/c")).isEqualTo("/a/..b/c");
    }

    @Test
    void decodedAppliesItToTheRequestPath() {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/v1/x/../platform/tenants");
        assertThat(RequestPaths.decoded(r)).isEqualTo("/api/v1/platform/tenants");
        assertThat(RequestPaths.canonical(new MockHttpServletRequest("GET", "/API/V1/./PLATFORM"))).isEqualTo("/api/v1/platform");
    }
}
