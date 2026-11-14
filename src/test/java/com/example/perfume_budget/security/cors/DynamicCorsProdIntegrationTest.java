package com.example.perfume_budget.security.cors;

import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.repository.TenantRepository;
import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import com.example.perfume_budget.support.TestTenants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

/**
 * DOMAIN-01 under the prod profile: https and the default port only, NO static origins and NO platform origins
 * configured (the documented prod defaults), so tenants work and the platform API is unreachable from a browser.
 */
@AutoConfigureMockMvc
@ActiveProfiles("prod")
@TestPropertySource(properties = {
        "tenant.base-domain=shopfi.test",
        "tenant.platform-domains=shopfi.example",
        "tenant.header-resolution.enabled=false",
        "cors.allowed-origins=",
        "platform.cors.allowed-origins=",
        "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=0"
})
class DynamicCorsProdIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TestTenants tenants;
    @Autowired TenantRepository tenantRepository;
    @Autowired CorsProperties corsProps;
    @Autowired PlatformCorsProperties platformCorsProps;

    private MockHttpServletResponse preflight(String path, String origin) throws Exception {
        return mvc.perform(options(path).header("Origin", origin).header("Access-Control-Request-Method", "GET"))
                .andReturn().getResponse();
    }

    @Test
    void prodAcceptsHttpsDefaultPortTenantsOnly_andHasNoDefaultOrigins() throws Exception {
        assertThat(corsProps.getAllowedOrigins()).isEmpty();
        assertThat(platformCorsProps.getAllowedOrigins()).isEmpty();
        tenants.acme();
        assertThat(preflight("/api/v1/category/all", "https://acme.shopfi.test").getStatus()).isEqualTo(200);
        assertThat(preflight("/api/v1/category/all", "https://acme.shopfi.test").getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
        for (String bad : List.of("http://acme.shopfi.test", "https://acme.shopfi.test:8443", "https://acme.shopfi.test:443",
                "http://acme.shopfi.test:3000", "http://localhost:3000", "https://localhost")) {
            assertThat(preflight("/api/v1/category/all", bad).getStatus()).as(bad).isEqualTo(403);
        }
    }

    @Test
    void prodPlatformApiIsUnreachableFromBrowsersUntilConfigured() throws Exception {
        tenants.acme();
        for (String origin : List.of("https://acme.shopfi.test", "https://shopfi.example", "https://console.example.com")) {
            assertThat(preflight("/api/v1/platform/auth/login", origin).getStatus()).as(origin).isEqualTo(403);
        }
        // onboarding from the apex (a platform domain) still works without credentials
        MockHttpServletResponse r = preflight("/api/v1/platform/onboarding/signup", "https://shopfi.example");
        assertThat(r.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://shopfi.example");
        assertThat(r.getHeader("Access-Control-Allow-Credentials")).isNull();
        assertThat(preflight("/api/v1/platform/onboarding/signup", "http://shopfi.example").getStatus()).isEqualTo(403);
        assertThat(preflight("/api/v1/platform/onboarding/signup", "https://shopfi.example:8443").getStatus()).isEqualTo(403);
    }

    @Test
    void suspendedTenantLosesCorsInProd_afterTheCacheTtl() throws Exception {
        tenants.ensureTenant("prodsusp");
        policyInvalidate();
        assertThat(preflight("/api/v1/category/all", "https://prodsusp.shopfi.test").getStatus()).isEqualTo(200);
        Tenant t = tenantRepository.findBySlug("prodsusp").orElseThrow();
        t.setStatus(TenantStatus.SUSPENDED);
        tenantRepository.save(t);
        policyInvalidate();
        assertThat(preflight("/api/v1/category/all", "https://prodsusp.shopfi.test").getStatus()).isEqualTo(403);
    }

    @Autowired TenantOriginPolicy policy;

    private void policyInvalidate() {
        policy.invalidateAll();
    }
}
