package com.example.perfume_budget.platform.auth;

import com.example.perfume_budget.filter.JWTUtil;
import com.example.perfume_budget.platform.model.PlatformAdmin;
import com.example.perfume_budget.platform.repository.PlatformAdminRepository;
import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import com.example.perfume_budget.support.TestTenant;
import com.example.perfume_budget.support.TestTenants;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** PLAT-01 end to end on the REAL filter chain (TenantResolutionFilter -> JWTFilter -> SecurityConfig) and Postgres. */
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "tenant.base-domain=shopfi.test",
        "cors.allowed-origins=https://acme.shopfi.test",
        "platform.cors.allowed-origins=https://console.shopfi.example",
        "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=0",
        "platform.auth.login-ip.capacity=8",
        "platform.auth.login-ip.period-seconds=3600",
        "platform.auth.login-email.capacity=3",
        "platform.auth.login-email.period-seconds=3600",
        "platform.auth.refresh-ip.capacity=3",
        "platform.auth.refresh-ip.period-seconds=3600"
})
class PlatformAuthIntegrationTest extends AbstractPostgresIntegrationTest {

    static final String PW = "Sup3r-Admin-pass!";
    static final String ACME_ORIGIN = "https://acme.shopfi.test";
    static final String CONSOLE_ORIGIN = "https://console.shopfi.example";
    static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired MockMvc mvc;
    @Autowired PlatformAdminRepository admins;
    @Autowired PasswordEncoder encoder;
    @Autowired JWTUtil jwtUtil;
    @Autowired TestTenants tenants;

    private static String nextIp() {
        int n = SEQ.incrementAndGet();
        return "10.20." + (n / 250) + "." + (n % 250 + 1);
    }

    private PlatformAdmin newAdmin(boolean active) {
        String email = "root" + SEQ.incrementAndGet() + "@platform.test";
        return admins.save(PlatformAdmin.builder().email(email).password(encoder.encode(PW))
                .fullName("Root").isActive(active).build());
    }

    private MockHttpServletResponse login(String email, String pw, String ip) throws Exception {
        return mvc.perform(post("/api/v1/platform/auth/login").with(r -> {
            r.setRemoteAddr(ip);
            return r;
        }).contentType("application/json").content("{\"email\":\"" + email + "\",\"password\":\"" + pw + "\"}"))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse loginOk(PlatformAdmin a) throws Exception {
        MockHttpServletResponse r = login(a.getEmail(), PW, nextIp());
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        return r;
    }

    private int status(MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b).andReturn().getResponse().getStatus();
    }

    private TestTenant acme() {
        return tenants.ensureTenant("acme");
    }

    private String tenantAccessToken() {
        TestTenant t = acme();
        return tenants.runAs(t, () -> jwtUtil.generateAccessToken(t.adminEmail(), "ADMIN", 1L, "Admin"));
    }

    private String tenantRefreshToken() {
        TestTenant t = acme();
        return tenants.runAs(t, () -> jwtUtil.generateRefreshToken(t.adminEmail(), "ADMIN", 1L, "Admin"));
    }

    private static Cookie asCookie(MockHttpServletResponse r, String name) {
        Cookie c = r.getCookie(name);
        assertThat(c).as("Set-Cookie " + name).isNotNull();
        return new Cookie(name, c.getValue());
    }

    // ---- AC1 login ------------------------------------------------------------------------------------------

    @Test
    void login_success_setsDistinctPlatformCookies_withPlatformClaimAndRole_noTokenInBody() throws Exception {
        PlatformAdmin a = newAdmin(true);
        MockHttpServletResponse r = loginOk(a);

        Cookie access = r.getCookie("platformAccessToken");
        Cookie refresh = r.getCookie("platformRefreshToken");
        assertThat(access).isNotNull();
        assertThat(refresh).isNotNull();
        assertThat(access.getPath()).isEqualTo("/api/v1/platform");
        assertThat(refresh.getPath()).isEqualTo("/api/v1/platform/auth");
        assertThat(access.isHttpOnly() && refresh.isHttpOnly()).isTrue();
        assertThat(access.getSecure() && refresh.getSecure()).isTrue();
        // never touches the tenant cookie names
        assertThat(r.getCookie("accessToken")).isNull();
        assertThat(r.getCookie("refreshToken")).isNull();
        assertThat(r.getHeaders("Set-Cookie")).allMatch(h -> h.startsWith("platform"));

        var claims = io.jsonwebtoken.Jwts.parser()
                .verifyWith(PlatformJwtService.deriveKey("404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970"))
                .build().parseSignedClaims(access.getValue()).getPayload();
        assertThat(claims.get("tenant")).isEqualTo("__platform__");
        assertThat(claims.get("role")).isEqualTo("SUPER_ADMIN");
        String body = r.getContentAsString();
        assertThat(body).contains(a.getEmail()).doesNotContain(access.getValue()).doesNotContain(refresh.getValue())
                .doesNotContain(PW).doesNotContain("password");
    }

    @Test
    void me_withPlatformCookie_is200_withoutIt_401() throws Exception {
        PlatformAdmin a = newAdmin(true);
        MockHttpServletResponse r = loginOk(a);
        var res = mvc.perform(get("/api/v1/platform/auth/me").cookie(asCookie(r, "platformAccessToken"))).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentAsString()).contains(a.getEmail()).contains("SUPER_ADMIN");
        assertThat(status(get("/api/v1/platform/auth/me"))).isEqualTo(401);
    }

    @Test
    void storedPasswordIsBcrypt_andLoginCaseInsensitiveOnEmail() throws Exception {
        PlatformAdmin a = newAdmin(true);
        assertThat(admins.findById(a.getId()).orElseThrow().getPassword()).startsWith("$2");
        assertThat(login(a.getEmail().toUpperCase(), PW, nextIp()).getStatus()).isEqualTo(200);
    }

    // ---- AC4 failures indistinguishable ----------------------------------------------------------------------

    @Test
    void unknownEmail_wrongPassword_andInactive_areIndistinguishable_noCookies() throws Exception {
        PlatformAdmin active = newAdmin(true);
        PlatformAdmin inactive = newAdmin(false);
        MockHttpServletResponse unknown = login("ghost" + SEQ.incrementAndGet() + "@platform.test", PW, nextIp());
        MockHttpServletResponse wrong = login(active.getEmail(), "Wrong-pass-123!", nextIp());
        MockHttpServletResponse inact = login(inactive.getEmail(), PW, nextIp());
        for (MockHttpServletResponse r : List.of(unknown, wrong, inact)) {
            assertThat(r.getStatus()).isEqualTo(401);
            assertThat(r.getHeaders("Set-Cookie")).isEmpty();
            assertThat(r.getContentAsString()).isEqualTo(unknown.getContentAsString());
        }
        assertThat(unknown.getContentAsString()).contains("Invalid email or password");
    }

    @Test
    void malformedLogin_is400_forEveryone() throws Exception {
        assertThat(login("", "", nextIp()).getStatus()).isEqualTo(400);
        assertThat(login("a@b.test", "x".repeat(73), nextIp()).getStatus()).isEqualTo(400);
    }

    // ---- AC4 throttling --------------------------------------------------------------------------------------

    @Test
    void perEmailThrottle_blocksFromManyIps_forKnownAndUnknownEmailsAlike() throws Exception {
        PlatformAdmin known = newAdmin(true);
        String unknown = "ghost" + SEQ.incrementAndGet() + "@platform.test";
        for (String email : List.of(known.getEmail(), unknown)) {
            for (int i = 0; i < 3; i++) {
                assertThat(login(email, "Wrong-pass-123!", nextIp()).getStatus()).isEqualTo(401);
            }
            MockHttpServletResponse blocked = login(email, "Wrong-pass-123!", nextIp());
            assertThat(blocked.getStatus()).isEqualTo(429);
            assertThat(blocked.getHeader("Retry-After")).isNotNull();
        }
        // even the right password is refused while throttled
        assertThat(login(known.getEmail(), PW, nextIp()).getStatus()).isEqualTo(429);
    }

    @Test
    void perIpThrottle_blocksAcrossDifferentEmails() throws Exception {
        String ip = nextIp();
        for (int i = 0; i < 8; i++) {
            assertThat(login("spray" + SEQ.incrementAndGet() + "@platform.test", "Wrong-pass-123!", ip).getStatus()).isEqualTo(401);
        }
        MockHttpServletResponse blocked = login("spray" + SEQ.incrementAndGet() + "@platform.test", "Wrong-pass-123!", ip);
        assertThat(blocked.getStatus()).isEqualTo(429);
        // another client is unaffected
        PlatformAdmin a = newAdmin(true);
        assertThat(login(a.getEmail(), PW, nextIp()).getStatus()).isEqualTo(200);
    }

    @Test
    void xForwardedForCannotBypassThePerIpThrottle() throws Exception {
        String ip = nextIp();
        for (int i = 0; i < 9; i++) {
            MockHttpServletResponse r = mvc.perform(post("/api/v1/platform/auth/login").with(rq -> {
                rq.setRemoteAddr(ip);
                return rq;
            }).header("X-Forwarded-For", "203.0.113." + i).contentType("application/json")
                    .content("{\"email\":\"xff" + SEQ.incrementAndGet() + "@platform.test\",\"password\":\"Wrong-pass-123!\"}"))
                    .andReturn().getResponse();
            assertThat(r.getStatus()).isEqualTo(i < 8 ? 401 : 429);
        }
    }

    // ---- AC2 cross-token rejection, both ways ---------------------------------------------------------------

    @Test
    void tenantToken_isRejectedOnPlatformPaths_inEitherCookie() throws Exception {
        Cookie tenantAsTenantCookie = new Cookie("accessToken", tenantAccessToken());
        Cookie tenantAsPlatformCookie = new Cookie("platformAccessToken", tenantAccessToken());
        for (String path : List.of("/api/v1/platform/auth/me", "/api/v1/platform/tenants", "/api/v1/platform/anything/else")) {
            // DOMAIN-01 / PLAT-03 AC3: a tenant origin is refused by CORS on platform paths (403) before any token check,
            // so the replay assertion keeps its power by using the platform console origin, which CORS accepts.
            assertThat(status(get(path).header("Origin", ACME_ORIGIN).cookie(tenantAsTenantCookie)))
                    .as("tenant origin on %s", path).isEqualTo(403);
            assertThat(status(get(path).header("Origin", CONSOLE_ORIGIN).cookie(tenantAsTenantCookie)))
                    .as("tenant cookie on %s", path).isEqualTo(401);
            assertThat(status(get(path).cookie(tenantAsTenantCookie))).as("no origin %s", path).isEqualTo(401);
            assertThat(status(get(path).cookie(tenantAsPlatformCookie))).as("tenant token in platform cookie %s", path).isEqualTo(401);
        }
    }

    @Test
    void platformToken_isRejectedOnTenantPaths_inEitherCookie() throws Exception {
        acme();
        MockHttpServletResponse login = loginOk(newAdmin(true));
        String token = login.getCookie("platformAccessToken").getValue();
        // positive control: the same tenant endpoints accept a genuine tenant token
        assertThat(status(get("/api/v1/auth/me").header("Origin", ACME_ORIGIN).cookie(new Cookie("accessToken", tenantAccessToken()))))
                .isEqualTo(200);
        for (String path : List.of("/api/v1/auth/me", "/api/v1/admin/metrics/customers", "/api/v1/payment-settings")) {
            assertThat(status(get(path).header("Origin", ACME_ORIGIN).cookie(new Cookie("accessToken", token))))
                    .as("platform token in tenant cookie on %s", path).isEqualTo(401);
            // platform cookie name on a tenant path is ignored: unauthenticated
            assertThat(status(get(path).header("Origin", ACME_ORIGIN).cookie(new Cookie("platformAccessToken", token))))
                    .as("platform cookie on %s", path).isEqualTo(401);
        }
    }

    @Test
    void platformToken_withDevTenantHeader_isAlsoRejectedOnTenantPaths() throws Exception {
        acme();
        String token = loginOk(newAdmin(true)).getCookie("platformAccessToken").getValue();
        assertThat(status(get("/api/v1/auth/me").header("X-Tenant-ID", "acme").cookie(new Cookie("accessToken", token)))).isEqualTo(401);
    }

    @Test
    void refreshTokenIsNotAnAccessToken_andViceVersa() throws Exception {
        MockHttpServletResponse login = loginOk(newAdmin(true));
        Cookie refreshAsAccess = new Cookie("platformAccessToken", login.getCookie("platformRefreshToken").getValue());
        assertThat(status(get("/api/v1/platform/auth/me").cookie(refreshAsAccess))).isEqualTo(401);
        Cookie accessAsRefresh = new Cookie("platformRefreshToken", login.getCookie("platformAccessToken").getValue());
        assertThat(status(post("/api/v1/platform/auth/refresh").with(r -> {
            r.setRemoteAddr(nextIp());
            return r;
        }).cookie(accessAsRefresh))).isEqualTo(401);
    }

    @Test
    void deactivatedAdmin_losesAccessImmediately_andCannotRefresh() throws Exception {
        PlatformAdmin a = newAdmin(true);
        MockHttpServletResponse login = loginOk(a);
        Cookie access = asCookie(login, "platformAccessToken");
        Cookie refresh = asCookie(login, "platformRefreshToken");
        assertThat(status(get("/api/v1/platform/auth/me").cookie(access))).isEqualTo(200);
        a.setActive(false);
        admins.save(a);
        assertThat(status(get("/api/v1/platform/auth/me").cookie(access))).isEqualTo(401);
        assertThat(status(post("/api/v1/platform/auth/refresh").with(r -> {
            r.setRemoteAddr(nextIp());
            return r;
        }).cookie(refresh))).isEqualTo(401);
    }

    // ---- refresh / logout ------------------------------------------------------------------------------------

    @Test
    void refresh_issuesNewAccessCookie_fromRefreshCookieOnly() throws Exception {
        MockHttpServletResponse login = loginOk(newAdmin(true));
        var res = mvc.perform(post("/api/v1/platform/auth/refresh").with(r -> {
            r.setRemoteAddr(nextIp());
            return r;
        }).cookie(asCookie(login, "platformRefreshToken"))).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getCookie("platformAccessToken")).isNotNull();
        assertThat(res.getCookie("accessToken")).isNull();
        // a tenant refresh cookie is not accepted
        assertThat(status(post("/api/v1/platform/auth/refresh").with(r -> {
            r.setRemoteAddr(nextIp());
            return r;
        }).cookie(new Cookie("refreshToken", tenantRefreshToken())))).isEqualTo(401);
        assertThat(status(post("/api/v1/platform/auth/refresh").with(r -> {
            r.setRemoteAddr(nextIp());
            return r;
        }).cookie(new Cookie("platformRefreshToken", tenantRefreshToken())))).isEqualTo(401);
    }

    @Test
    void refreshIsThrottledPerIp_andOtherIpsAreUnaffected() throws Exception {
        String ip = nextIp();
        for (int i = 0; i < 3; i++) {
            assertThat(refreshStatus(ip)).as("attempt %d", i).isEqualTo(401);
        }
        var blocked = mvc.perform(post("/api/v1/platform/auth/refresh").with(r -> {
            r.setRemoteAddr(ip);
            return r;
        })).andReturn().getResponse();
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isNotNull();
        assertThat(refreshStatus(nextIp())).isEqualTo(401);
    }

    private int refreshStatus(String ip) throws Exception {
        return status(post("/api/v1/platform/auth/refresh").with(r -> {
            r.setRemoteAddr(ip);
            return r;
        }));
    }

    // ---- S7: BCrypt 72-byte limit enforced in bytes -----------------------------------------------------------

    private PlatformAdmin adminWithPassword(String pw) {
        return admins.save(PlatformAdmin.builder().email("bytes" + SEQ.incrementAndGet() + "@platform.test")
                .password(encoder.encode(pw)).fullName("Root").isActive(true).build());
    }

    @Test
    void passwordLimitIsInBytes_asciiBoundary72Ok_73Rejected_andTruncationInputCannotLogIn() throws Exception {
        String p72 = "a".repeat(72);
        PlatformAdmin a = adminWithPassword(p72);
        assertThat(login(a.getEmail(), p72, nextIp()).getStatus()).isEqualTo(200);
        MockHttpServletResponse over = login(a.getEmail(), p72 + "x", nextIp());
        assertThat(over.getStatus()).as("73 bytes sharing the first 72 must not authenticate").isEqualTo(400);
        assertThat(over.getCookie("platformAccessToken")).isNull();
    }

    @Test
    void passwordLimitIsInBytes_multibyteBoundary() throws Exception {
        String p72 = "\u00e9".repeat(36); // 36 chars, 72 bytes
        PlatformAdmin a = adminWithPassword(p72);
        assertThat(login(a.getEmail(), p72, nextIp()).getStatus()).isEqualTo(200);
        // 37 chars (passes the 72-CHARACTER cap) but 74 bytes, sharing the first 72 bytes with the stored password
        assertThat(login(a.getEmail(), p72 + "\u00e9", nextIp()).getStatus()).isEqualTo(400);
        assertThat(login(a.getEmail(), "\u00e9".repeat(37), nextIp()).getStatus()).isEqualTo(400);
    }

    // ---- S8: exp and iat are mandatory ---------------------------------------------------------------------

    @Test
    void platformKeyTokenWithoutExpOrIat_isRejectedOnMe() throws Exception {
        PlatformAdmin a = newAdmin(true);
        var key = PlatformJwtService.deriveKey("404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970");
        String good = io.jsonwebtoken.Jwts.builder().subject(a.getEmail()).claim("tenant", "__platform__")
                .claim("role", "SUPER_ADMIN").claim("typ", "access").issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + 60_000)).signWith(key).compact();
        String noExp = io.jsonwebtoken.Jwts.builder().subject(a.getEmail()).claim("tenant", "__platform__")
                .claim("role", "SUPER_ADMIN").claim("typ", "access").issuedAt(new java.util.Date()).signWith(key).compact();
        String noIat = io.jsonwebtoken.Jwts.builder().subject(a.getEmail()).claim("tenant", "__platform__")
                .claim("role", "SUPER_ADMIN").claim("typ", "access")
                .expiration(new java.util.Date(System.currentTimeMillis() + 60_000)).signWith(key).compact();
        assertThat(status(get("/api/v1/platform/auth/me").cookie(new Cookie("platformAccessToken", good)))).as("control").isEqualTo(200);
        assertThat(status(get("/api/v1/platform/auth/me").cookie(new Cookie("platformAccessToken", noExp)))).isEqualTo(401);
        assertThat(status(get("/api/v1/platform/auth/me").cookie(new Cookie("platformAccessToken", noIat)))).isEqualTo(401);
    }

    // ---- S10: case-insensitive admin lookup ------------------------------------------------------------------

    @Test
    void adminStoredWithMixedCaseEmail_canLogIn_andUseTheSession_andCannotBeDuplicated() throws Exception {
        String mixed = "MiXed" + SEQ.incrementAndGet() + "@Platform.test";
        admins.save(PlatformAdmin.builder().email(mixed).password(encoder.encode(PW)).fullName("Mixed").isActive(true).build());
        for (String typed : List.of(mixed, mixed.toLowerCase(), mixed.toUpperCase())) {
            MockHttpServletResponse r = login(typed, PW, nextIp());
            assertThat(r.getStatus()).as(typed).isEqualTo(200);
            assertThat(status(get("/api/v1/platform/auth/me").cookie(asCookie(r, "platformAccessToken")))).isEqualTo(200);
        }
        // a validly signed token whose subject differs in case from the stored email still resolves the admin
        var key = PlatformJwtService.deriveKey("404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970");
        String lowerSubject = io.jsonwebtoken.Jwts.builder().subject(mixed.toLowerCase()).claim("tenant", "__platform__")
                .claim("role", "SUPER_ADMIN").claim("typ", "access").issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + 60_000)).signWith(key).compact();
        assertThat(status(get("/api/v1/platform/auth/me").cookie(new Cookie("platformAccessToken", lowerSubject)))).isEqualTo(200);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> admins.saveAndFlush(PlatformAdmin.builder()
                .email(mixed.toLowerCase()).password("x").fullName("Dup").isActive(true).build()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // ---- cookies do not interfere -----------------------------------------------------------------------------

    @Test
    void tenantLoginAfterPlatformLogin_keepsBothSessions_andLogoutsOnlyClearTheirOwnCookies() throws Exception {
        TestTenant t = acme();
        MockHttpServletResponse platform = loginOk(newAdmin(true));
        MockHttpServletResponse tenant = mvc.perform(post("/api/v1/auth/login").header("Origin", ACME_ORIGIN)
                .contentType("application/json")
                .content("{\"email\":\"" + t.adminEmail() + "\",\"password\":\"" + t.adminPassword() + "\"}"))
                .andReturn().getResponse();
        assertThat(tenant.getStatus()).as(tenant.getContentAsString()).isEqualTo(200);
        assertThat(tenant.getCookie("platformAccessToken")).isNull();

        Cookie[] both = {asCookie(platform, "platformAccessToken"), asCookie(platform, "platformRefreshToken"),
                asCookie(tenant, "accessToken"), asCookie(tenant, "refreshToken")};
        assertThat(status(get("/api/v1/platform/auth/me").cookie(both))).isEqualTo(200);
        assertThat(status(get("/api/v1/auth/me").header("Origin", ACME_ORIGIN).cookie(both))).isEqualTo(200);

        MockHttpServletResponse platformLogout = mvc.perform(post("/api/v1/platform/auth/logout").cookie(both)).andReturn().getResponse();
        assertThat(platformLogout.getStatus()).isEqualTo(200);
        assertThat(platformLogout.getHeaders("Set-Cookie")).hasSize(2).allMatch(h -> h.startsWith("platform") && h.contains("Max-Age=0"));
        MockHttpServletResponse tenantLogout = mvc.perform(post("/api/v1/auth/logout").header("Origin", ACME_ORIGIN).cookie(both))
                .andReturn().getResponse();
        assertThat(tenantLogout.getHeaders("Set-Cookie")).allMatch(h -> !h.startsWith("platform"));
    }

    // ---- public paths, neutral paths unaffected ----------------------------------------------------------------

    @Test
    void staleOrGarbagePlatformCookie_doesNotBreakLoginOrPublicOnboarding() throws Exception {
        PlatformAdmin a = newAdmin(true);
        Cookie junk = new Cookie("platformAccessToken", "garbage.value.here");
        assertThat(status(post("/api/v1/platform/auth/login").cookie(junk).with(r -> {
            r.setRemoteAddr(nextIp());
            return r;
        }).contentType("application/json").content("{\"email\":\"" + a.getEmail() + "\",\"password\":\"" + PW + "\"}"))).isEqualTo(200);
        int onboarding = status(get("/api/v1/platform/onboarding/status").param("slug", "nosuchshop").cookie(junk).with(r -> {
            r.setRemoteAddr(nextIp());
            return r;
        }));
        assertThat(onboarding).as("public onboarding is not gated by platform cookies").isNotIn(401, 403);
        assertThat(status(get("/api/v1/platform/auth/me").cookie(junk))).isEqualTo(401);
    }

    @Test
    void neutralPaths_actuatorHealthAndOauth2_behaveTheSameWithAPlatformCookie() throws Exception {
        String token = loginOk(newAdmin(true)).getCookie("platformAccessToken").getValue();
        Cookie platform = new Cookie("platformAccessToken", token);
        Cookie asTenantCookie = new Cookie("accessToken", token);
        for (String path : List.of("/actuator/health", "/oauth2/authorize/google", "/ws/info")) {
            int base = status(get(path));
            assertThat(status(get(path).cookie(platform))).as(path).isEqualTo(base);
            assertThat(status(get(path).cookie(asTenantCookie))).as(path + " (platform token in tenant cookie)").isEqualTo(base);
        }
    }

    @Test
    void anonymousPlatformRequest_isUnauthorized_neverReachesAHandler() throws Exception {
        assertThat(status(get("/api/v1/platform/tenants"))).isEqualTo(401);
        assertThat(status(post("/api/v1/platform/tenants/x/suspend"))).isEqualTo(401);
    }
}
