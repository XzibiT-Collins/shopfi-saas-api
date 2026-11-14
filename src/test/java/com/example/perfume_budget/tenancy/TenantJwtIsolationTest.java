package com.example.perfume_budget.tenancy;

import com.example.perfume_budget.config.DataSeeder;
import com.example.perfume_budget.filter.JWTUtil;
import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.repository.TenantRepository;
import com.example.perfume_budget.repository.UserRepository;
import com.example.perfume_budget.security.CustomUserDetailsService;
import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import com.example.perfume_budget.support.TestTenant;
import com.example.perfume_budget.support.TestTenants;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * TENANT-03 AC3, AC4, AC5: full filter chain (TenantResolutionFilter -> SiteVisitFilter -> JWTFilter ->
 * controllers) over MockMvc against the two seeded tenants acme and bravo.
 * UserRepository / CustomUserDetailsService are spies so "401 before any tenant-local user lookup"
 * is asserted on actual invocations, with positive controls proving the spies observe real lookups.
 */
@AutoConfigureMockMvc
// CORS uses an exact-match origin list (SecurityConfig), so the tenant origins used below are listed explicitly.
@TestPropertySource(properties = {
        "tenant.base-domain=shopfi.test",
        "tenant.header-resolution.enabled=true",
        "cors.allowed-origins=https://acme.shopfi.test,https://bravo.shopfi.test,https://susp.shopfi.test,https://nobody.shopfi.test"
})
class TenantJwtIsolationTest extends AbstractPostgresIntegrationTest {

    static final String ACME_ORIGIN = "https://acme.shopfi.test";
    static final String BRAVO_ORIGIN = "https://bravo.shopfi.test";

    @Autowired MockMvc mvc;
    @Autowired JWTUtil jwtUtil;
    @Autowired TestTenants tenants;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSeeder dataSeeder;
    @MockitoSpyBean UserRepository userRepository;
    @MockitoSpyBean CustomUserDetailsService userDetailsService;

    TestTenant acme;
    TestTenant bravo;

    @BeforeEach
    void setUp() {
        acme = tenants.acme();
        bravo = tenants.bravo();
        tenants.ensureTenant("susp");
        Tenant susp = tenantRepository.findBySlug("susp").orElseThrow();
        susp.setStatus(TenantStatus.SUSPENDED);
        tenantRepository.save(susp);
    }

    private void clearLookups() {
        Mockito.clearInvocations(userRepository, userDetailsService);
    }

    private void verifyNoUserLookup() {
        verify(userDetailsService, never()).loadUserByUsername(anyString());
        verify(userRepository, never()).findByEmail(anyString());
        Mockito.verifyNoMoreInteractions(userDetailsService);
    }

    private String accessTokenFor(TestTenant t) {
        return tenants.runAs(t, () -> jwtUtil.generateAccessToken(t.adminEmail(), "ADMIN", 1L, "Admin"));
    }

    private String refreshTokenFor(TestTenant t) {
        return tenants.runAs(t, () -> jwtUtil.generateRefreshToken(t.adminEmail(), "ADMIN", 1L, "Admin"));
    }

    private static Cookie cookie(MockHttpServletResponse res, String name) {
        Cookie c = res.getCookie(name);
        assertThat(c).as("Set-Cookie %s", name).isNotNull();
        return c;
    }

    // ---- AC3: claim on access + refresh -------------------------------------------------------------------

    @Test
    void ac3_tokensIssuedViaPasswordLogin_carryTenantClaim_accessAndRefresh() throws Exception {
        for (TestTenant t : new TestTenant[]{acme, bravo}) {
            clearLookups();
            MvcResult r = mvc.perform(post("/api/v1/auth/login")
                            .header("Origin", "https://" + t.slug() + ".shopfi.test")
                            .contentType("application/json")
                            .content("{\"email\":\"" + t.adminEmail() + "\",\"password\":\"" + t.adminPassword() + "\"}"))
                    .andReturn();
            MockHttpServletResponse res = r.getResponse();
            assertThat(res.getStatus()).as("login body: %s", res.getContentAsString()).isEqualTo(200);
            assertThat(res.getContentAsString()).as("expected direct login, not an OTP challenge")
                    .doesNotContain("\"requiresOtp\":true");
            assertThat(jwtUtil.extractTenant(cookie(res, "accessToken").getValue())).isEqualTo(t.slug());
            assertThat(jwtUtil.extractTenant(cookie(res, "refreshToken").getValue())).isEqualTo(t.slug());
        }
    }

    @Test
    void ac3_mintedAccessAndRefreshTokens_carryResolvedTenantSlug() {
        assertThat(jwtUtil.extractTenant(accessTokenFor(acme))).isEqualTo("acme");
        assertThat(jwtUtil.extractTenant(refreshTokenFor(acme))).isEqualTo("acme");
        assertThat(jwtUtil.extractTenant(accessTokenFor(bravo))).isEqualTo("bravo");
        assertThat(jwtUtil.extractTenant(refreshTokenFor(bravo))).isEqualTo("bravo");
    }

    @Test
    void ac3_sameTokenOnItsOwnTenant_authenticates_positiveControl() throws Exception {
        clearLookups();
        mvc.perform(get("/api/v1/auth/me").header("Origin", ACME_ORIGIN)
                        .cookie(new Cookie("accessToken", accessTokenFor(acme))))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200));
        verify(userDetailsService, Mockito.atLeastOnce()).loadUserByUsername(acme.adminEmail());
    }

    @Test
    void ac3_acmeAccessToken_withBravoOrigin_is401_beforeAnyUserLookup() throws Exception {
        String token = accessTokenFor(acme);
        clearLookups();
        mvc.perform(get("/api/v1/auth/me").header("Origin", BRAVO_ORIGIN).cookie(new Cookie("accessToken", token)))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
        verifyNoUserLookup();
    }

    @Test
    void ac3_bravoAccessToken_withAcmeOrigin_is401_beforeAnyUserLookup() throws Exception {
        String token = accessTokenFor(bravo);
        clearLookups();
        mvc.perform(get("/api/v1/auth/me").header("Origin", ACME_ORIGIN).cookie(new Cookie("accessToken", token)))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
        verifyNoUserLookup();
    }

    @Test
    void ac3_replayViaRefererOrDevHeader_isAlso401_beforeAnyUserLookup() throws Exception {
        String token = accessTokenFor(acme);
        clearLookups();
        mvc.perform(get("/api/v1/auth/me").header("Referer", BRAVO_ORIGIN + "/cart").cookie(new Cookie("accessToken", token)))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
        mvc.perform(get("/api/v1/auth/me").header("X-Tenant-ID", "bravo").cookie(new Cookie("accessToken", token)))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
        verifyNoUserLookup();
    }

    @Test
    void ac3_replayOnPublicTenantPath_isAlso401() throws Exception {
        // Not only /me: a mismatching cookie on a permitAll path is rejected by JWTFilter too.
        String token = accessTokenFor(acme);
        clearLookups();
        mvc.perform(get("/api/v1/category").header("Origin", BRAVO_ORIGIN).cookie(new Cookie("accessToken", token)))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
        verifyNoUserLookup();
    }

    @Test
    void ac3_tokenWithoutTenantClaim_isRejectedOnATenantScopedRequest() throws Exception {
        // legacy / forged-by-key-holder token with no tenant claim must not authenticate inside a tenant
        TenantContext.clear();
        String legacy = jwtUtil.generateAccessToken(acme.adminEmail(), "ADMIN", 1L, "Admin");
        assertThat(jwtUtil.extractTenant(legacy)).isNull();
        clearLookups();
        mvc.perform(get("/api/v1/auth/me").header("Origin", ACME_ORIGIN).cookie(new Cookie("accessToken", legacy)))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
        verifyNoUserLookup();
    }

    // ---- AC4: refresh token replay -----------------------------------------------------------------------

    @Test
    void ac4_refresh_onOwnTenant_issuesAccessTokenBoundToThatTenant_positiveControl() throws Exception {
        clearLookups();
        MvcResult r = mvc.perform(post("/api/v1/auth/refresh").header("Origin", ACME_ORIGIN)
                .cookie(new Cookie("refreshToken", refreshTokenFor(acme)))).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(jwtUtil.extractTenant(cookie(r.getResponse(), "accessToken").getValue())).isEqualTo("acme");
    }

    @Test
    void ac4_acmeRefreshToken_cannotRefreshOnBravo_401_andNoUserLookup() throws Exception {
        String refresh = refreshTokenFor(acme);
        clearLookups();
        MvcResult r = mvc.perform(post("/api/v1/auth/refresh").header("Origin", BRAVO_ORIGIN)
                .cookie(new Cookie("refreshToken", refresh))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(r.getResponse().getCookie("accessToken")).as("no access token may be issued").isNull();
        verifyNoUserLookup();
    }

    @Test
    void ac4_bravoRefreshToken_cannotRefreshOnAcme_401_andNoUserLookup() throws Exception {
        String refresh = refreshTokenFor(bravo);
        clearLookups();
        MvcResult r = mvc.perform(post("/api/v1/auth/refresh").header("Origin", ACME_ORIGIN)
                .cookie(new Cookie("refreshToken", refresh))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(r.getResponse().getCookie("accessToken")).isNull();
        verifyNoUserLookup();
    }

    @Test
    void ac4_refreshTokenReplayViaDevHeader_isAlso401() throws Exception {
        String refresh = refreshTokenFor(acme);
        clearLookups();
        MvcResult r = mvc.perform(post("/api/v1/auth/refresh").header("X-Tenant-ID", "bravo")
                .cookie(new Cookie("refreshToken", refresh))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        verifyNoUserLookup();
    }

    @Test
    void ac4_refreshTokenWithoutTenantClaim_cannotRefreshInsideATenant() throws Exception {
        TenantContext.clear();
        String legacy = jwtUtil.generateRefreshToken(acme.adminEmail(), "ADMIN", 1L, "Admin");
        clearLookups();
        MvcResult r = mvc.perform(post("/api/v1/auth/refresh").header("Origin", ACME_ORIGIN)
                .cookie(new Cookie("refreshToken", legacy))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        verifyNoUserLookup();
    }

    // ---- AC5: suspended + no-origin on tenant-only paths --------------------------------------------------

    @Test
    void ac5_suspendedTenant_returns423_evenWithValidTokenForThatTenant_andNoUserLookup() throws Exception {
        TestTenant susp = tenants.ensureTenant("susp");
        String token = accessTokenFor(susp);
        clearLookups();
        mvc.perform(get("/api/v1/auth/me").header("Origin", "https://susp.shopfi.test").cookie(new Cookie("accessToken", token)))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(423));
        mvc.perform(post("/api/v1/auth/login").header("Origin", "https://susp.shopfi.test")
                        .contentType("application/json")
                        .content("{\"email\":\"" + susp.adminEmail() + "\",\"password\":\"" + susp.adminPassword() + "\"}"))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(423));
        verifyNoUserLookup();
    }

    @Test
    void ac5_unknownOrigin_is404_throughFullChain() throws Exception {
        mvc.perform(get("/api/v1/category").header("Origin", "https://nobody.shopfi.test"))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(404));
    }

    private int siteVisitsInTemplate() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM tenant_template.site_visits", Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    void ac5_noOriginNoReferer_onTenantOnlyPath_doesNotTouchDefaultSchema_siteVisits() throws Exception {
        // header resolution is enabled here but no X-Tenant-ID is sent: there is no tenant signal at all.
        int before = siteVisitsInTemplate();
        mvc.perform(get("/api/v1/category").header("X-Forwarded-For", "203.0.113." + (int) (Math.random() * 200 + 1)));
        assertThat(siteVisitsInTemplate())
                .as("a request with no tenant signal must not write/read tenant_template (SiteVisitFilter ran neutral)")
                .isEqualTo(before);
    }

    @Test
    void ac5_noOriginNoReferer_tokenlessLogin_mustNotAuthenticateAgainstDefaultSchemaUsers() throws Exception {
        String email = "sentinel-default@template.test";
        String password = "Sentinel!123";
        TenantContext.set(TenantIdentity.template());
        try {
            dataSeeder.seedAdmin(email, password, "Sentinel");
        } finally {
            TenantContext.clear();
        }
        try {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_template.users WHERE email = ?", Integer.class, email)).isEqualTo(1);
            MvcResult r = mvc.perform(post("/api/v1/auth/login")
                    .contentType("application/json")
                    .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")).andReturn();
            assertThat(r.getResponse().getStatus())
                    .as("login without any tenant signal must be rejected, not served from tenant_template. body=%s",
                            r.getResponse().getContentAsString())
                    .isGreaterThanOrEqualTo(400);
        } finally {
            jdbc.update("DELETE FROM tenant_template.users WHERE email = ?", email);
        }
    }

    @Test
    void ac5_noOriginNoReferer_tokenWithoutClaim_mustNotAuthenticateAgainstDefaultSchemaUsers() throws Exception {
        String email = "sentinel-token@template.test";
        TenantContext.set(TenantIdentity.template());
        try {
            dataSeeder.seedAdmin(email, "Sentinel!123", "Sentinel");
        } finally {
            TenantContext.clear();
        }
        try {
            String token = jwtUtil.generateAccessToken(email, "ADMIN", 1L, "Sentinel"); // no tenant claim
            MvcResult r = mvc.perform(get("/api/v1/auth/me").cookie(new Cookie("accessToken", token))).andReturn();
            assertThat(r.getResponse().getStatus())
                    .as("a claim-less token with no tenant signal must not resolve a user from tenant_template. body=%s",
                            r.getResponse().getContentAsString())
                    .isEqualTo(401);
        } finally {
            jdbc.update("DELETE FROM tenant_template.users WHERE email = ?", email);
        }
    }
}
