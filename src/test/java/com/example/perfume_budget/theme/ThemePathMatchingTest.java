package com.example.perfume_budget.theme;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** THEME-04c AC4: the path is matched on the decoded/normalized servlet path, never the raw URI. */
class ThemePathMatchingTest {

    private static MockHttpServletRequest req(String rawUri, String decodedServletPath) {
        MockHttpServletRequest r = new MockHttpServletRequest("PUT", rawUri);
        r.setServletPath(decodedServletPath); // what Tomcat computes: percent-decoded, normalized
        return r;
    }

    @Test
    void percentEncodedPrefixIsRecognised() {
        assertThat(ThemeRequestSizeFilter.isThemePath(req("/api/v1/%74heme", "/api/v1/theme"))).isTrue();
        assertThat(ThemeRequestSizeFilter.isThemePath(req("/api/v1/theme", "/api/v1/theme"))).isTrue();
    }

    @Test
    void otherPathsAreNot() {
        assertThat(ThemeRequestSizeFilter.isThemePath(req("/api/v1/product", "/api/v1/product"))).isFalse();
        assertThat(ThemeRequestSizeFilter.isThemePath(req("/api/v1/%74heme/../product", "/api/v1/product"))).isFalse();
    }
}
