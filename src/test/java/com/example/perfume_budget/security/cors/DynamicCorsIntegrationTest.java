package com.example.perfume_budget.security.cors;

import com.example.perfume_budget.config.ws.TenantOriginHandshakeInterceptor;
import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.repository.TenantRepository;
import com.example.perfume_budget.platform.service.TenantRegistryService;
import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import com.example.perfume_budget.support.TestTenants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * DOMAIN-01 on the REAL filter chain and Postgres (non-prod profile: http and ports are allowed for tenants).
 * Tenants: acme, bravo (ACTIVE), dsusp (SUSPENDED), dcustom (ACTIVE, custom domain shop.dcustom.example),
 * dcustomsusp (SUSPENDED, custom domain old.dcustom.example), dflip (flipped during the TTL test).
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "tenant.base-domain=shopfi.test",
        "tenant.platform-domains=shopfi.example,api.shopfi.example",
        "cors.allowed-origins=https://static.example.com",
        "platform.cors.allowed-origins=https://console.example.com",
        "cors.tenant-cache.ttl-seconds=1",
        "cors.tenant-cache.negative-ttl-seconds=1",
        "cors.tenant-cache.max-lookups-per-second=20",
        // every MockMvc request comes from 127.0.0.1: the per-client budget (unit-tested in TenantOriginPolicyTest) is
        // raised here so the shared-address test traffic is governed by the global budget exactly as before
        "cors.tenant-cache.max-lookups-per-ip-per-second=1000",
        "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=0"
})
class DynamicCorsIntegrationTest extends AbstractPostgresIntegrationTest {

    static final String ACME = "https://acme.shopfi.test";
    static final String BRAVO = "https://bravo.shopfi.test";
    static final String CONSOLE = "https://console.example.com";
    static final String APEX = "https://shopfi.example";
    static final String STATIC = "https://static.example.com";
    static final String TENANT_PATH = "/api/v1/category/all";

    @Autowired MockMvc mvc;
    @Autowired TestTenants tenants;
    @Autowired TenantRepository tenantRepository;
    @Autowired TenantOriginPolicy policy;
    @Autowired DynamicTenantCorsConfigurationSource source;
    @MockitoSpyBean TenantRegistryService registry;

    @BeforeEach
    void fixtures() {
        tenants.acme();
        tenants.bravo();
        status("dsusp", TenantStatus.SUSPENDED, null);
        status("dcustom", TenantStatus.ACTIVE, "shop.dcustom.example");
        status("dcustomsusp", TenantStatus.SUSPENDED, "old.dcustom.example");
        status("dflip", TenantStatus.ACTIVE, null);
        policy.invalidateAll();
        policy.resetBudgets(); // the flood test drains the shared lookup bucket; do not depend on wall-clock refill
    }

    private void status(String slug, TenantStatus status, String domain) {
        tenants.ensureTenant(slug);
        Tenant t = tenantRepository.findBySlug(slug).orElseThrow();
        t.setStatus(status);
        t.setCustomDomain(domain);
        tenantRepository.save(t);
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b).andReturn().getResponse();
    }

    private MockHttpServletResponse preflight(String path, String origin, String method, String headers) throws Exception {
        MockHttpServletRequestBuilder b = options(path).header("Origin", origin).header("Access-Control-Request-Method", method);
        if (headers != null) {
            b.header("Access-Control-Request-Headers", headers);
        }
        return call(b);
    }

    private static void assertNoCorsGrant(MockHttpServletResponse r, String what) {
        assertThat(r.getHeader("Access-Control-Allow-Origin")).as(what + " ACAO").isNull();
        assertThat(r.getHeader("Access-Control-Allow-Credentials")).as(what + " ACAC").isNull();
        assertThat(r.getHeader("Access-Control-Expose-Headers")).as(what + " expose").isNull();
    }

    private static void assertNeverWildcard(MockHttpServletResponse r) {
        for (String h : List.of("Access-Control-Allow-Origin", "Access-Control-Allow-Headers", "Access-Control-Allow-Methods",
                "Access-Control-Expose-Headers")) {
            for (String v : r.getHeaders(h)) {
                assertThat(v.contains("*")).as(h + "=" + v).isFalse();
            }
        }
    }

    // ---- tenant paths: allowed ---------------------------------------------------------------------------------

    @Test
    void activeTenantOrigin_getsCredentialedCors_exposedHeaders_andVaryOrigin() throws Exception {
        for (String origin : List.of(ACME, BRAVO)) {
            MockHttpServletResponse r = call(get(TENANT_PATH).header("Origin", origin));
            assertThat(r.getStatus()).as(origin).isEqualTo(200);
            assertThat(r.getHeader("Access-Control-Allow-Origin")).isEqualTo(origin);
            assertThat(r.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
            assertThat(r.getHeader("Access-Control-Expose-Headers")).contains("ETag").contains("Retry-After");
            assertThat(String.join(",", r.getHeaders("Vary"))).contains("Origin");
            assertNeverWildcard(r);
        }
    }

    @Test
    void customDomainOfActiveTenant_isAllowed_andUppercaseHostIsNormalised() throws Exception {
        MockHttpServletResponse r = call(get(TENANT_PATH).header("Origin", "https://shop.dcustom.example"));
        assertThat(r.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://shop.dcustom.example");
        assertThat(r.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
        MockHttpServletResponse up = call(get(TENANT_PATH).header("Origin", "https://ACME.Shopfi.Test"));
        assertThat(up.getStatus()).isEqualTo(200);
        assertThat(up.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://ACME.Shopfi.Test");
    }

    @Test
    void staticListOrigin_isAllowedOnTenantPaths_withCredentials() throws Exception {
        MockHttpServletResponse r = preflight(TENANT_PATH, STATIC, "GET", null);
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getHeader("Access-Control-Allow-Origin")).isEqualTo(STATIC);
        assertThat(r.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
    }

    @Test
    void requestIsStillResolvedByOrigin_otherTenantsOriginIsOnlyItsOwn() throws Exception {
        // bravo's origin is CORS-allowed, and the request is bound to bravo (not acme): product list is per tenant
        MockHttpServletResponse r = call(get(TENANT_PATH).header("Origin", BRAVO));
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getHeader("Access-Control-Allow-Origin")).isEqualTo(BRAVO);
        assertThat(r.getHeader("Access-Control-Allow-Origin")).isNotEqualTo(ACME);
    }

    // ---- tenant paths: rejected --------------------------------------------------------------------------------

    @Test
    void disallowedOrigins_get403_noCorsGrant_andVaryOrigin() throws Exception {
        List<String> bad = List.of(
                "https://evil.com", "https://acme.shopfi.test.evil.com", "https://evilshopfi.test",
                "https://acme.shopfi.test@evil.com", "https://user@acme.shopfi.test", "https://acme.shopfi.test:0",
                "https://acme.shopfi.test:99999", "null", "", "https://acme.shopfi.test.", "https://acme.shopfi.test/x",
                "https://a.b.shopfi.test", "https://www.shopfi.test", "https://api.shopfi.test", "https://admin.shopfi.test",
                "https://platform.shopfi.test", "https://ghost.shopfi.test", "https://xn--acme-9ua.shopfi.test",
                "https://acmé.shopfi.test", "https://dsusp.shopfi.test", "https://old.dcustom.example",
                "https://shopfi.test", "https://*.shopfi.test", "*", "https://static.example.com.evil.com",
                "https://shop.dcustom.example.evil.com");
        for (String origin : bad) {
            for (MockHttpServletRequestBuilder b : List.of(get(TENANT_PATH), post("/api/v1/auth/login"))) {
                MockHttpServletResponse r = call(b.header("Origin", origin));
                assertThat(r.getStatus()).as("actual request, origin '%s'", origin).isEqualTo(403);
                assertNoCorsGrant(r, origin);
            }
            MockHttpServletResponse p = preflight(TENANT_PATH, origin, "GET", null);
            assertThat(p.getStatus()).as("preflight '%s'", origin).isEqualTo(403);
            assertNoCorsGrant(p, origin);
            assertThat(p.getHeader("Access-Control-Allow-Methods")).isNull();
        }
        assertThat(String.join(",", call(get(TENANT_PATH).header("Origin", "https://evil.com")).getHeaders("Vary"))).contains("Origin");
    }

    @Test
    void multipleOriginHeaders_areRejected_evenWhenBothAreValidTenants() throws Exception {
        MockHttpServletResponse r = call(get(TENANT_PATH).header("Origin", ACME).header("Origin", BRAVO));
        assertThat(r.getStatus()).isEqualTo(403);
        assertNoCorsGrant(r, "multi");
        MockHttpServletResponse p = call(options(TENANT_PATH).header("Origin", ACME).header("Origin", ACME)
                .header("Access-Control-Request-Method", "GET"));
        assertThat(p.getStatus()).isEqualTo(403);
    }

    @Test
    void suspendedTenantOrigin_getsNoCorsHeaders_customDomainToo() throws Exception {
        for (String origin : List.of("https://dsusp.shopfi.test", "https://old.dcustom.example")) {
            assertNoCorsGrant(call(get(TENANT_PATH).header("Origin", origin)), origin);
            assertNoCorsGrant(preflight(TENANT_PATH, origin, "GET", null), origin);
        }
    }

    // ---- preflight ---------------------------------------------------------------------------------------------

    @Test
    void tenantPreflight_isExplicit_minimal_andNeverWildcard() throws Exception {
        MockHttpServletResponse r = preflight(TENANT_PATH, ACME, "PUT", "Content-Type, If-Match");
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getHeader("Access-Control-Allow-Origin")).isEqualTo(ACME);
        assertThat(r.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
        assertThat(r.getHeader("Access-Control-Allow-Methods")).contains("PUT").contains("DELETE").contains("PATCH");
        assertThat(r.getHeader("Access-Control-Allow-Headers")).contains("Content-Type").contains("If-Match");
        assertThat(r.getHeader("Access-Control-Max-Age")).isEqualTo("300");
        assertThat(String.join(",", r.getHeaders("Vary"))).contains("Origin");
        assertNeverWildcard(r);

        // headers/methods outside the explicit list are refused (no Authorization: cookies are the credential)
        assertThat(preflight(TENANT_PATH, ACME, "GET", "Authorization").getStatus()).isEqualTo(403);
        assertThat(preflight(TENANT_PATH, ACME, "GET", "X-Evil").getStatus()).isEqualTo(403);
        assertThat(preflight(TENANT_PATH, ACME, "TRACE", null).getStatus()).isEqualTo(403);
    }

    @Test
    void nonProdAcceptsHttpAndAnExplicitPortForActiveTenants() throws Exception {
        assertThat(preflight(TENANT_PATH, "http://acme.shopfi.test:3000", "GET", null).getStatus()).isEqualTo(200);
        assertThat(preflight(TENANT_PATH, "http://ghost.shopfi.test:3000", "GET", null).getStatus()).isEqualTo(403);
    }

    @Test
    void devTenantHeaderIsAllowedCrossOriginOnlyOutsideProd() throws Exception {
        // pgtest enables header resolution in a non-prod profile, so X-Tenant-ID is a permitted request header here
        assertThat(preflight(TENANT_PATH, ACME, "GET", "X-Tenant-ID").getStatus()).isEqualTo(200);
    }

    // ---- platform paths (PLAT-03 AC3) --------------------------------------------------------------------------

    @Test
    void platformPaths_rejectTenantAndStaticOrigins_evenValidOnes() throws Exception {
        for (String path : List.of("/api/v1/platform/auth/login", "/api/v1/platform/auth/me", "/api/v1/platform/tenants",
                "/api/v1/platform")) {
            for (String origin : List.of(ACME, BRAVO, STATIC, "https://shop.dcustom.example", APEX)) {
                MockHttpServletResponse pf = preflight(path, origin, "POST", "Content-Type");
                assertThat(pf.getStatus()).as("preflight %s from %s", path, origin).isEqualTo(403);
                assertNoCorsGrant(pf, path + origin);
                MockHttpServletResponse r = call(get(path).header("Origin", origin));
                assertThat(r.getStatus()).as("%s from %s", path, origin).isEqualTo(403);
                assertNoCorsGrant(r, path + origin);
            }
        }
    }

    @Test
    void platformPaths_allowOnlyTheConsoleOrigin_withCredentials() throws Exception {
        MockHttpServletResponse pf = preflight("/api/v1/platform/auth/login", CONSOLE, "POST", "Content-Type");
        assertThat(pf.getStatus()).isEqualTo(200);
        assertThat(pf.getHeader("Access-Control-Allow-Origin")).isEqualTo(CONSOLE);
        assertThat(pf.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
        MockHttpServletResponse r = call(get("/api/v1/platform/auth/me").header("Origin", CONSOLE));
        assertThat(r.getStatus()).isEqualTo(401); // reached the chain: unauthenticated, but CORS-allowed
        assertThat(r.getHeader("Access-Control-Allow-Origin")).isEqualTo(CONSOLE);
        assertThat(r.getHeader("Access-Control-Expose-Headers")).contains("Retry-After");
        assertNeverWildcard(r);
        // the console origin is not a tenant origin
        assertThat(call(get(TENANT_PATH).header("Origin", CONSOLE)).getStatus()).isEqualTo(403);
    }

    // ---- public onboarding -------------------------------------------------------------------------------------

    @Test
    void onboarding_allowsApexAndConsole_withoutCredentials_andRefusesTenantOrigins() throws Exception {
        for (String origin : List.of(APEX, "https://api.shopfi.example", CONSOLE)) {
            MockHttpServletResponse pf = preflight("/api/v1/platform/onboarding/signup", origin, "POST", "Content-Type");
            assertThat(pf.getStatus()).as(origin).isEqualTo(200);
            assertThat(pf.getHeader("Access-Control-Allow-Origin")).isEqualTo(origin);
            assertThat(pf.getHeader("Access-Control-Allow-Credentials")).as("no credentials flag " + origin).isNull();
            assertNeverWildcard(pf);
            MockHttpServletResponse r = call(get("/api/v1/platform/onboarding/status").header("Origin", origin));
            assertThat(r.getHeader("Access-Control-Allow-Origin")).isEqualTo(origin);
            assertThat(r.getHeader("Access-Control-Allow-Credentials")).isNull();
            assertThat(r.getHeader("Access-Control-Expose-Headers")).contains("Retry-After");
        }
        for (String origin : List.of(ACME, STATIC, "https://evil.com", "https://shopfi.example.evil.com", "null")) {
            assertThat(preflight("/api/v1/platform/onboarding/signup", origin, "POST", "Content-Type").getStatus())
                    .as(origin).isEqualTo(403);
        }
        // non-GET/POST methods and extra headers are refused on onboarding
        assertThat(preflight("/api/v1/platform/onboarding/signup", APEX, "DELETE", null).getStatus()).isEqualTo(403);
        assertThat(preflight("/api/v1/platform/onboarding/signup", APEX, "POST", "If-Match").getStatus()).isEqualTo(403);
        // the apex is not a tenant origin
        assertThat(call(get(TENANT_PATH).header("Origin", APEX)).getStatus()).isEqualTo(403);
    }

    // ---- cache behaviour ---------------------------------------------------------------------------------------

    @Test
    void suspensionFlipsTheDecisionWithinTheTtl() throws Exception {
        String origin = "https://dflip.shopfi.test";
        assertThat(preflight(TENANT_PATH, origin, "GET", null).getStatus()).isEqualTo(200);
        status("dflip", TenantStatus.SUSPENDED, null);
        // still cached inside the TTL window (documented behaviour), the flip happens after it
        long deadline = System.currentTimeMillis() + 5_000;
        int s = 200;
        while (s == 200 && System.currentTimeMillis() < deadline) {
            Thread.sleep(250);
            s = preflight(TENANT_PATH, origin, "GET", null).getStatus();
        }
        assertThat(s).as("suspended tenant must lose CORS within the TTL (1 s in this test)").isEqualTo(403);
        status("dflip", TenantStatus.ACTIVE, null);
        Thread.sleep(1_300); // denied decision TTL (1 s) so the reactivation is picked up
        assertThat(preflight(TENANT_PATH, origin, "GET", null).getStatus()).isEqualTo(200);
    }

    @Test
    void repeatedOriginsAndUnknownSlugFloodsDoNotHitTheRegistryPerRequest() throws Exception {
        clearInvocations(registry);
        for (int i = 0; i < 30; i++) {
            preflight(TENANT_PATH, ACME, "GET", null);
            preflight(TENANT_PATH, "https://ghost.shopfi.test", "GET", null);
        }
        verify(registry, times(1)).findBySlug("acme");
        verify(registry, times(1)).findBySlug("ghost");

        clearInvocations(registry);
        for (int i = 0; i < 300; i++) {
            preflight(TENANT_PATH, "https://rand" + i + ".shopfi.test", "GET", null);
            preflight(TENANT_PATH, "https://rand" + i + ".garbage.example", "GET", null);
        }
        // 20 lookups per second budget (+ at most one refill during the loop), far below the 600 requests
        verify(registry, atMost(60)).findBySlug(startsWith("rand"));
        verify(registry, atMost(60)).findByCustomDomain(startsWith("rand"));
        // syntactically invalid origins and reserved slugs never reach the registry
        clearInvocations(registry);
        for (String o : List.of("null", "https://a.b.shopfi.test", "https://www.shopfi.test", "https://acme.shopfi.test:99999",
                "https://acme.shopfi.test@evil.com", "https://xn--x.shopfi.test")) {
            preflight(TENANT_PATH, o, "GET", null);
        }
        verify(registry, never()).findBySlug(anyString());
        verify(registry, never()).findByCustomDomain(anyString());
    }

    // ---- WebSocket ---------------------------------------------------------------------------------------------

    @Test
    void webSocketEndpoint_usesTheSameOriginDecision() throws Exception {
        MockHttpServletResponse ok = call(get("/ws/info").header("Origin", ACME));
        assertThat(ok.getStatus()).isEqualTo(200);
        assertThat(ok.getHeader("Access-Control-Allow-Origin")).isEqualTo(ACME);
        assertThat(ok.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
        for (String bad : List.of("https://evil.com", "https://dsusp.shopfi.test", "https://acme.shopfi.test.evil.com", "null")) {
            MockHttpServletResponse r = call(get("/ws/info").header("Origin", bad));
            assertThat(r.getStatus()).as(bad).isEqualTo(403);
            assertNoCorsGrant(r, bad);
            assertThat(call(get("/ws/websocket").header("Origin", bad)).getStatus()).as("raw " + bad).isEqualTo(403);
        }
    }

    @Test
    void webSocketHandshakeInterceptor_rejectsDisallowedOrigins_onItsOwn() {
        TenantOriginHandshakeInterceptor interceptor = new TenantOriginHandshakeInterceptor(policy);
        Map<String, Object> attrs = new HashMap<>();
        assertThat(handshake(interceptor, ACME, attrs)).isTrue();
        assertThat(handshake(interceptor, null, attrs)).as("no Origin = non-browser client").isTrue();
        for (String bad : List.of("https://evil.com", "https://dsusp.shopfi.test", "null", "https://a.b.shopfi.test")) {
            MockHttpServletResponse raw = new MockHttpServletResponse();
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ws/websocket");
            req.addHeader("Origin", bad);
            boolean ok = interceptor.beforeHandshake(new ServletServerHttpRequest(req), new ServletServerHttpResponse(raw), null, attrs);
            assertThat(ok).as(bad).isFalse();
            assertThat(raw.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        }
        MockHttpServletRequest two = new MockHttpServletRequest("GET", "/ws/websocket");
        two.addHeader("Origin", ACME);
        two.addHeader("Origin", BRAVO);
        assertThat(interceptor.beforeHandshake(new ServletServerHttpRequest(two),
                new ServletServerHttpResponse(new MockHttpServletResponse()), null, attrs)).isFalse();
    }

    private boolean handshake(TenantOriginHandshakeInterceptor i, String origin, Map<String, Object> attrs) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ws/websocket");
        if (origin != null) {
            req.addHeader("Origin", origin);
        }
        return i.beforeHandshake(new ServletServerHttpRequest(req), new ServletServerHttpResponse(new MockHttpServletResponse()), null, attrs);
    }

    @Test
    void sourceReturnsNullWithoutAnOriginHeader() {
        assertThat(source.getCorsConfiguration(new MockHttpServletRequest("GET", TENANT_PATH))).isNull();
    }
}
