package com.example.perfume_budget.security.cors;

import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.service.TenantRegistryService;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantResolutionFilter;
import com.example.perfume_budget.tenancy.TenantResolutionProperties;
import com.example.perfume_budget.tenancy.TenantScope;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** DOMAIN-01b S1: every zone decision uses the DECODED path, independently of the 400 guard filter. */
class RequestPathsTest {

    static final List<String> ENCODED = List.of("/api/v1/%70latform/auth/login", "/api/v1/plat%66orm/auth/login",
            "/api/v1/platform/%6fnboarding/x", "/api/v1/%50LATFORM/auth/login", "/api/v1/platform//auth/login",
            "/api/v1/platform/auth;x=y/login");

    private static MockHttpServletRequest req(String rawUri) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", rawUri);
        r.setRequestURI(rawUri);
        return r;
    }

    @Test
    void obfuscatedPlatformPathsAreDetected_plainOnesAreNot() {
        for (String uri : ENCODED) {
            assertThat(RequestPaths.isObfuscatedPlatformPath(req(uri))).as(uri).isTrue();
        }
        for (String uri : List.of("/api/v1/platform/auth/login", "/api/v1/platform/onboarding/signup",
                "/api/v1/category/all", "/api/v1/product/a%20b", "/ws/info")) {
            assertThat(RequestPaths.isObfuscatedPlatformPath(req(uri))).as(uri).isFalse();
        }
    }

    @Test
    void canonicalPathIsDecodedAndLowerCased() {
        assertThat(RequestPaths.canonical(req("/api/v1/%70latform/%6fnboarding/x"))).isEqualTo("/api/v1/platform/onboarding/x");
        assertThat(RequestPaths.decoded(req("/api/v1/%50latform/x"))).isEqualTo("/api/v1/Platform/x");
    }

    @Test
    void corsZoneIsChosenOnTheDecodedPath_evenWithoutTheGuardFilter() {
        TenantOriginPolicy policy = mock(TenantOriginPolicy.class);
        when(policy.isAllowed(anyString(), anyString())).thenReturn(true); // would allow any origin in the tenant zone
        DynamicTenantCorsConfigurationSource source = new DynamicTenantCorsConfigurationSource(policy, new CorsProperties(),
                new PlatformCorsProperties(), new TenantResolutionProperties(), new MockEnvironment());
        for (String uri : List.of("/api/v1/%70latform/auth/login", "/api/v1/plat%66orm/auth/login", "/api/v1/%50LATFORM/tenants")) {
            MockHttpServletRequest r = req(uri);
            r.addHeader("Origin", "https://acme.shopfi.test");
            CorsConfiguration c = source.getCorsConfiguration(r);
            assertThat(c.getAllowedOrigins()).as("platform zone, tenant origin denied: " + uri).isEmpty();
        }
        // encoded onboarding segment must land in the credential-less onboarding zone, not the credentialed platform zone
        PlatformCorsProperties pp = new PlatformCorsProperties();
        pp.setAllowedOrigins(List.of("https://console.example.com"));
        DynamicTenantCorsConfigurationSource withConsole = new DynamicTenantCorsConfigurationSource(policy, new CorsProperties(),
                pp, new TenantResolutionProperties(), new MockEnvironment());
        MockHttpServletRequest onb = req("/api/v1/platform/%6fnboarding/x");
        onb.addHeader("Origin", "https://console.example.com");
        assertThat(withConsole.getCorsConfiguration(onb).getAllowCredentials()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void tenantResolutionBindsPlatformScopeOnTheDecodedPath() throws Exception {
        TenantRegistryService registry = mock(TenantRegistryService.class);
        when(registry.findBySlug(anyString())).thenReturn(java.util.Optional.<Tenant>empty());
        Environment env = new MockEnvironment();
        TenantResolutionFilter filter = new TenantResolutionFilter(registry, new TenantResolutionProperties(), env);
        AtomicReference<TenantScope> seen = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest rq, jakarta.servlet.ServletResponse rs) {
                seen.set(TenantContext.get() == null ? null : TenantContext.get().scope());
            }
        };
        MockHttpServletRequest r = req("/api/v1/%70latform/auth/login");
        r.addHeader("Origin", "https://acme.shopfi.test");
        filter.doFilter(r, new MockHttpServletResponse(), chain);
        assertThat(seen.get()).isEqualTo(TenantScope.PLATFORM);
        Mockito.verifyNoInteractions(registry);
    }
}
