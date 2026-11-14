package com.example.perfume_budget.tenancy;

import com.example.perfume_budget.enums.AuthProvider;
import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.filter.JWTUtil;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.notification.MailMessage;
import com.example.perfume_budget.notification.MailTransport;
import com.example.perfume_budget.repository.UserRepository;
import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import com.example.perfume_budget.support.FakeRedis;
import com.example.perfume_budget.support.TestTenant;
import com.example.perfume_budget.support.TestTenants;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AUTH-01 on the REAL filter chain (TenantResolutionFilter -> JWTFilter -> SecurityConfig -> controllers) and
 * Postgres, with the two tenants acme and bravo. The auth cookie lives on the single API host, so a browser that
 * visited shop A sends A's cookies to shop B: nothing about that may block login, logout, refresh or public reads.
 * Redis is an in-memory fake (keys inspectable) and the mail transport is captured.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "tenant.base-domain=shopfi.test",
        "tenant.header-resolution.enabled=true",
        "cors.allowed-origins=https://acme.shopfi.test,https://bravo.shopfi.test",
        "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=0",
        "application.auth.rate-limit.resend-otp-ip.capacity=6",
        "application.auth.rate-limit.resend-otp-ip.period-seconds=3600",
        "application.auth.rate-limit.resend-otp-email.capacity=3",
        "application.auth.rate-limit.resend-otp-email.period-seconds=3600"
})
class AuthTenantFixesIntegrationTest extends AbstractPostgresIntegrationTest {

    static final String ACME = "https://acme.shopfi.test";
    static final String BRAVO = "https://bravo.shopfi.test";
    static final String JSON = "application/json";
    static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired MockMvc mvc;
    @Autowired JWTUtil jwtUtil;
    @Autowired TestTenants tenants;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder encoder;
    @Value("${application.security.jwt.secret-key}") String jwtSecret;
    // spy (not mock): RedisKeyValueAdapter needs the real template's connection factory at context start
    @MockitoSpyBean RedisTemplate<String, Object> redisTemplate;
    @MockitoBean MailTransport mailTransport;

    TestTenant acme;
    TestTenant bravo;
    FakeRedis redis;

    @BeforeEach
    void setUp() {
        acme = tenants.acme();
        bravo = tenants.bravo();
        redis = FakeRedis.wire(redisTemplate);
        Mockito.clearInvocations(mailTransport);
    }

    // ------------------------------------------------------------------------------------------ helpers

    static String origin(TestTenant t) {
        return "https://" + t.slug() + ".shopfi.test";
    }

    private MockHttpServletRequestBuilder with(MockHttpServletRequestBuilder b, String origin, Cookie... cookies) {
        b.header("Origin", origin);
        if (cookies.length > 0) {
            b.cookie(cookies);
        }
        return b;
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b).andReturn().getResponse();
    }

    private MockHttpServletResponse login(TestTenant t, String email, String password, Cookie... cookies) throws Exception {
        return call(with(post("/api/v1/auth/login"), origin(t), cookies).contentType(JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private Cookie[] sessionOf(TestTenant t) throws Exception {
        MockHttpServletResponse r = login(t, t.adminEmail(), t.adminPassword());
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        return new Cookie[]{r.getCookie("accessToken"), r.getCookie("refreshToken")};
    }

    private static Cookie named(Cookie[] cookies, String name) {
        for (Cookie c : cookies) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        throw new AssertionError("no cookie " + name);
    }

    private String expiredAccessToken(TestTenant t) {
        Date past = new Date(System.currentTimeMillis() - 3_600_000);
        return Jwts.builder().claims().add("tenant", t.slug()).add("role", "ADMIN").and()
                .subject(t.adminEmail()).issuedAt(new Date(past.getTime() - 1000)).expiration(past)
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret))).compact();
    }

    private String tokenWithTenantClaim(String tenantClaim, String email) {
        var b = Jwts.builder().subject(email).issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 600_000));
        if (tenantClaim != null) {
            b.claim("tenant", tenantClaim);
        }
        return b.signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret))).compact();
    }

    private void assertJson401(MockHttpServletResponse r) throws Exception {
        assertThat(r.getStatus()).isEqualTo(401);
        assertThat(r.getContentType()).as("never plain text").startsWith(JSON);
        String body = r.getContentAsString();
        assertThat(body).contains("\"description\"");
        assertThat(body.toLowerCase()).as("no tenant wording that could leak other tenants")
                .doesNotContain("mismatch").doesNotContain("tenant").doesNotContain("acme").doesNotContain("bravo");
    }

    private void assertCleared(MockHttpServletResponse r) {
        for (String n : new String[]{"accessToken", "refreshToken"}) {
            Cookie c = r.getCookie(n);
            assertThat(c).as("Set-Cookie %s", n).isNotNull();
            assertThat(c.getMaxAge()).as("%s cleared", n).isZero();
        }
    }

    /** The whole public + auth surface on tenant {@code on} while {@code cookies} (unusable here) are attached. */
    private void assertTenantUsableWith(TestTenant on, boolean refreshShouldWork, Cookie... cookies) throws Exception {
        String o = origin(on);
        MockHttpServletResponse pub = call(with(get("/api/v1/category/all"), o, cookies));
        assertThat(pub.getStatus()).as("public GET with an unusable cookie: %s", pub.getContentAsString()).isEqualTo(200);

        MockHttpServletResponse me = call(with(get("/api/v1/auth/me"), o, cookies));
        assertJson401(me);

        MockHttpServletResponse refresh = call(with(post("/api/v1/auth/refresh"), o, cookies));
        if (refreshShouldWork) {
            assertThat(refresh.getStatus()).as(refresh.getContentAsString()).isEqualTo(200);
            assertThat(jwtUtil.extractTenant(refresh.getCookie("accessToken").getValue())).isEqualTo(on.slug());
        } else {
            assertJson401(refresh);
            assertThat(refresh.getCookie("accessToken")).as("no access token issued").isNull();
        }

        MockHttpServletResponse forgot = call(with(post("/api/v1/auth/forgot-password"), o, cookies).contentType(JSON)
                .content("{\"email\":\"nobody@x.test\"}"));
        assertThat(forgot.getStatus()).isEqualTo(200);

        MockHttpServletResponse verify = call(with(post("/api/v1/auth/verify-otp"), o, cookies).contentType(JSON)
                .content("{\"email\":\"nobody@x.test\",\"otp\":\"123456\"}"));
        assertThat(verify.getStatus()).as("a bad OTP is a 400, never a cookie 401: %s", verify.getContentAsString()).isEqualTo(400);

        String email = "reg" + SEQ.incrementAndGet() + "@" + on.slug() + ".test";
        MockHttpServletResponse reg = call(with(post("/api/v1/auth/register"), o, cookies).contentType(JSON).content(
                "{\"email\":\"" + email + "\",\"fullName\":\"R G\",\"password\":\"Abcdef1!\",\"confirmPassword\":\"Abcdef1!\"}"));
        assertThat(reg.getStatus()).as(reg.getContentAsString()).isEqualTo(204);

        MockHttpServletResponse logout = call(with(post("/api/v1/auth/logout"), o, cookies));
        assertThat(logout.getStatus()).isEqualTo(204);
        assertCleared(logout);

        MockHttpServletResponse in = login(on, on.adminEmail(), on.adminPassword(), cookies);
        assertThat(in.getStatus()).as("login must work: %s", in.getContentAsString()).isEqualTo(200);
        assertThat(jwtUtil.extractTenant(in.getCookie("accessToken").getValue())).isEqualTo(on.slug());
        assertThat(jwtUtil.extractTenant(in.getCookie("refreshToken").getValue())).isEqualTo(on.slug());
    }

    // ------------------------------------------------------------------------------------------ AC2

    @Test
    void cookiesOfTenantA_doNotBlockTenantB_andViceVersa() throws Exception {
        Cookie[] a = sessionOf(acme);
        Cookie[] b = sessionOf(bravo);
        assertTenantUsableWith(bravo, false, a);
        assertTenantUsableWith(acme, false, b);
        // positive control: each tenant's own cookies still authenticate there
        assertThat(call(with(get("/api/v1/auth/me"), ACME, a)).getStatus()).isEqualTo(200);
        assertThat(call(with(get("/api/v1/auth/me"), BRAVO, b)).getStatus()).isEqualTo(200);
    }

    @Test
    void onlyTheAccessCookieIsForeign_refreshWithTheOwnRefreshCookieStillWorks() throws Exception {
        Cookie[] a = sessionOf(acme);
        Cookie[] b = sessionOf(bravo);
        // browser state after visiting both shops where only the access cookie is stale/foreign
        assertTenantUsableWith(bravo, true, named(a, "accessToken"), named(b, "refreshToken"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired-own", "garbage", "foreign-signature", "platform-claim", "no-tenant-claim", "empty"})
    void unusableAccessCookie_isAnonymous_onPublicAndAuthPaths_andJson401OnProtected(String kind) throws Exception {
        String token = switch (kind) {
            case "expired-own" -> expiredAccessToken(bravo);
            case "garbage" -> "not-a-jwt";
            case "foreign-signature" -> Jwts.builder().subject(bravo.adminEmail()).claim("tenant", "bravo")
                    .expiration(new Date(System.currentTimeMillis() + 600_000))
                    .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(
                            "ZGlmZmVyZW50LXNlY3JldC1rZXktZGlmZmVyZW50LXNlY3JldC1rZXktMTIzNDU2")))
                    .compact();
            case "platform-claim" -> tokenWithTenantClaim(TenantContext.PLATFORM_SLUG, bravo.adminEmail());
            case "no-tenant-claim" -> tokenWithTenantClaim(null, bravo.adminEmail());
            default -> "";
        };
        Cookie[] own = sessionOf(bravo);
        // access cookie unusable; the refresh cookie is bravo's valid one: refresh must work (expired access)
        assertTenantUsableWith(bravo, true, new Cookie("accessToken", token), named(own, "refreshToken"));
        // and with no refresh cookie at all it is a clean JSON 401 on refresh
        assertTenantUsableWith(bravo, false, new Cookie("accessToken", token));
    }

    @Test
    void expiredAccessWithValidRefresh_thenTheNewAccessCookieAuthenticates() throws Exception {
        Cookie[] own = sessionOf(acme);
        MockHttpServletResponse r = call(with(post("/api/v1/auth/refresh"), ACME,
                new Cookie("accessToken", expiredAccessToken(acme)), named(own, "refreshToken")));
        assertThat(r.getStatus()).isEqualTo(200);
        Cookie fresh = r.getCookie("accessToken");
        assertThat(call(with(get("/api/v1/auth/me"), ACME, fresh)).getStatus()).isEqualTo(200);
    }

    @Test
    void refreshWithoutAnyCookie_is401Json_not400() throws Exception {
        assertJson401(call(with(post("/api/v1/auth/refresh"), ACME)));
        assertJson401(call(with(post("/api/v1/auth/refresh"), ACME, new Cookie("refreshToken", ""))));
        assertJson401(call(with(post("/api/v1/auth/refresh"), ACME, new Cookie("refreshToken", "junk"))));
    }

    @Test
    void refreshCookieFromAnotherTenant_isSimplyInvalid() throws Exception {
        Cookie[] a = sessionOf(acme);
        MockHttpServletResponse r = call(with(post("/api/v1/auth/refresh"), BRAVO, named(a, "refreshToken")));
        assertJson401(r);
        assertThat(r.getCookie("accessToken")).isNull();
    }

    @Test
    void refreshCookieFromAnotherTenant_isInvalid_evenWhenTheSameEmailExistsOnThisTenant() throws Exception {
        // The dangerous case: if only the user lookup rejected it, a same-email account on B would receive a session.
        String email = "twin" + SEQ.incrementAndGet() + "@x.test";
        newUser(acme, email, "Twinpass1!a", true, UserRole.CUSTOMER);
        newUser(bravo, email, "Twinpass1!b", true, UserRole.CUSTOMER);
        MockHttpServletResponse in = login(acme, email, "Twinpass1!a");
        assertThat(in.getStatus()).isEqualTo(200);

        MockHttpServletResponse r = call(with(post("/api/v1/auth/refresh"), BRAVO, in.getCookie("refreshToken")));
        assertJson401(r);
        assertThat(r.getCookie("accessToken")).isNull();
        // and A's access cookie never authenticates the same-email account on B
        assertJson401(call(with(get("/api/v1/auth/me"), BRAVO, in.getCookie("accessToken"))));
    }

    @Test
    void logoutAlwaysClearsCookies_evenWithoutAnyCookie() throws Exception {
        assertCleared(call(with(post("/api/v1/auth/logout"), ACME)));
        assertCleared(call(with(post("/api/v1/auth/logout"), BRAVO, new Cookie("accessToken", "garbage"))));
    }

    @Test
    void protectedPathWithForeignCookie_isStructuredJson401() throws Exception {
        Cookie[] a = sessionOf(acme);
        assertJson401(call(with(get("/api/v1/delivery-detail"), BRAVO, a)));
        assertJson401(call(with(get("/api/v1/auth/me"), BRAVO, a)));
    }

    @Test
    void cookieMaxAge_comesFromTheJwtLifetimes() throws Exception {
        MockHttpServletResponse r = login(acme, acme.adminEmail(), acme.adminPassword());
        assertThat(r.getCookie("accessToken").getMaxAge()).isEqualTo(jwtUtil.accessTokenTtlSeconds()).isEqualTo(3600);
        assertThat(r.getCookie("refreshToken").getMaxAge()).isEqualTo(jwtUtil.refreshTokenTtlSeconds()).isEqualTo(86400);
        assertThat(r.getCookie("refreshToken").getMaxAge()).as("was a hard-coded 7 days").isNotEqualTo(604800);
        MockHttpServletResponse refreshed = call(with(post("/api/v1/auth/refresh"), ACME, r.getCookie("refreshToken")));
        assertThat(refreshed.getCookie("accessToken").getMaxAge()).isEqualTo(jwtUtil.accessTokenTtlSeconds());
    }

    // ------------------------------------------------------------------------------------------ AC1

    private User newUser(TestTenant t, String email, String password, boolean verified, UserRole role) {
        return tenants.runAs(t, () -> userRepository.findByEmail(email).orElseGet(() -> userRepository.save(User.builder()
                .fullName("Shared User").email(email).password(encoder.encode(password)).roles(role)
                .authProvider(AuthProvider.LOCAL).isActive(true).emailVerified(verified).build())));
    }

    private static final Pattern LINK = Pattern.compile(
            "https://([a-z0-9-]+)\\.shopfi\\.test/reset-password\\?resetPasswordToken=([A-Za-z0-9_-]+)");

    private Matcher requestResetLink(TestTenant t, String email) throws Exception {
        Mockito.clearInvocations(mailTransport);
        MockHttpServletResponse r = call(with(post("/api/v1/auth/forgot-password"), origin(t)).contentType(JSON)
                .content("{\"email\":\"" + email + "\"}"));
        assertThat(r.getStatus()).isEqualTo(200);
        ArgumentCaptor<MailMessage> c = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailTransport, timeout(8000)).send(c.capture());
        Matcher m = LINK.matcher(c.getValue().html());
        assertThat(m.find()).as("reset link in the email html").isTrue();
        return m;
    }

    @Test
    void resetLinks_differPerTenant_andTokensAreUsableOnlyOnTheirTenant() throws Exception {
        String email = "shared" + SEQ.incrementAndGet() + "@x.test";
        newUser(acme, email, "Oldpass1!acme", true, UserRole.CUSTOMER);
        newUser(bravo, email, "Oldpass1!bravo", true, UserRole.CUSTOMER);

        Matcher forAcme = requestResetLink(acme, email);
        String acmeHost = forAcme.group(1);
        String acmeToken = forAcme.group(2);
        Matcher forBravo = requestResetLink(bravo, email);
        String bravoHost = forBravo.group(1);
        String bravoToken = forBravo.group(2);

        assertThat(acmeHost).isEqualTo("acme");
        assertThat(bravoHost).isEqualTo("bravo");
        assertThat(acmeToken).isNotEqualTo(bravoToken);
        assertThat(redis.store.keySet()).filteredOn(k -> k.startsWith("otp:reset:"))
                .allMatch(k -> k.startsWith("otp:reset:acme:") || k.startsWith("otp:reset:bravo:"));

        String body = "{\"password\":\"Newpass1!x\",\"confirmPassword\":\"Newpass1!x\"}";
        // acme's token on bravo's host: refused, and bravo's password is untouched
        MockHttpServletResponse wrong = call(with(post("/api/v1/auth/reset-password"), BRAVO)
                .queryParam("resetPasswordToken", acmeToken).contentType(JSON).content(body));
        assertThat(wrong.getStatus()).as(wrong.getContentAsString()).isBetween(400, 499);
        assertThat(login(bravo, email, "Oldpass1!bravo").getStatus()).isEqualTo(200);
        assertThat(login(bravo, email, "Newpass1!x").getStatus()).isEqualTo(401);

        // the token works on its own tenant
        MockHttpServletResponse ok = call(with(post("/api/v1/auth/reset-password"), ACME)
                .queryParam("resetPasswordToken", acmeToken).contentType(JSON).content(body));
        assertThat(ok.getStatus()).as(ok.getContentAsString()).isEqualTo(200);
        assertThat(login(acme, email, "Newpass1!x").getStatus()).isEqualTo(200);
        // single use
        assertThat(call(with(post("/api/v1/auth/reset-password"), ACME).queryParam("resetPasswordToken", acmeToken)
                .contentType(JSON).content(body)).getStatus()).isBetween(400, 499);
        // bravo's own token still works for bravo only
        assertThat(call(with(post("/api/v1/auth/reset-password"), ACME).queryParam("resetPasswordToken", bravoToken)
                .contentType(JSON).content(body)).getStatus()).isBetween(400, 499);
        assertThat(call(with(post("/api/v1/auth/reset-password"), BRAVO).queryParam("resetPasswordToken", bravoToken)
                .contentType(JSON).content(body)).getStatus()).isEqualTo(200);
    }

    @Test
    void resetEncodesTheStrippedPassword_soLoginWorksWithOrWithoutEdgeWhitespace() throws Exception {
        String email = "strip" + SEQ.incrementAndGet() + "@x.test";
        newUser(acme, email, "Oldpass1!acme", true, UserRole.CUSTOMER);
        String token = requestResetLink(acme, email).group(2);

        String padded = "  Padded1!pw \\t";
        MockHttpServletResponse ok = call(with(post("/api/v1/auth/reset-password"), ACME).queryParam("resetPasswordToken", token)
                .contentType(JSON).content("{\"password\":\"" + padded + "\",\"confirmPassword\":\"Padded1!pw\"}"));
        assertThat(ok.getStatus()).as("equal after strip: %s", ok.getContentAsString()).isEqualTo(200);
        assertThat(login(acme, email, "Padded1!pw").getStatus()).as("stored hash is of the stripped password").isEqualTo(200);
    }

    @Test
    void resetRejectsWeakPasswords_withThePolicyMessages() throws Exception {
        MockHttpServletResponse r = call(with(post("/api/v1/auth/reset-password"), ACME).queryParam("resetPasswordToken", "x")
                .contentType(JSON).content("{\"password\":\"Ab1!\",\"confirmPassword\":\"Ab1!\"}"));
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(r.getContentAsString()).contains("at least 8");
    }

    @Test
    void oauth2Failure_redirectsWithAFixedCode_neverTheExceptionText() throws Exception {
        MockHttpServletResponse r = call(get("/oauth2/callback/google")
                .queryParam("error", "access_denied").queryParam("error_description", "SECRET-DETAIL")
                .queryParam("state", "abc"));
        assertThat(r.getStatus()).isEqualTo(302);
        String loc = r.getRedirectedUrl();
        assertThat(loc).contains("error=oauth2_failed");
        assertThat(loc).doesNotContain("SECRET").doesNotContain("access_denied").doesNotContain("authorization_request");
    }

    // ------------------------------------------------------------------------------------------ AC3

    private MockHttpServletResponse resend(TestTenant t, String email, String ip) throws Exception {
        MockHttpServletRequestBuilder b = with(post("/api/v1/auth/resend-otp"), origin(t)).contentType(JSON)
                .content("{\"email\":\"" + email + "\"}");
        b.with(req -> {
            req.setRemoteAddr(ip);
            return req;
        });
        return call(b);
    }

    @Test
    void resendOtp_unknownAndKnownAndVerifiedLookIdentical_andThePendingOneIsActuallyEmailed() throws Exception {
        String pending = "pending" + SEQ.incrementAndGet() + "@x.test";
        String verified = "verified" + SEQ.incrementAndGet() + "@x.test";
        String unknown = "ghost" + SEQ.incrementAndGet() + "@x.test";
        newUser(acme, pending, "Oldpass1!acme", false, UserRole.CUSTOMER);
        newUser(acme, verified, "Oldpass1!acme", true, UserRole.CUSTOMER);

        MockHttpServletResponse p = resend(acme, pending, "10.20.0.1");
        MockHttpServletResponse v = resend(acme, verified, "10.20.0.2");
        MockHttpServletResponse u = resend(acme, unknown, "10.20.0.3");

        assertThat(p.getStatus()).isEqualTo(200);
        assertThat(v.getStatus()).isEqualTo(p.getStatus());
        assertThat(u.getStatus()).isEqualTo(p.getStatus());
        assertThat(v.getContentAsString()).isEqualTo(p.getContentAsString());
        assertThat(u.getContentAsString()).isEqualTo(p.getContentAsString());
        assertThat(p.getContentType()).isEqualTo(u.getContentType());
        // only the pending account is emailed; every path does the same Redis work (decoy for the others)
        verify(mailTransport, timeout(8000)).send(argThat(m -> m != null && m.to().equals(pending)));
        Mockito.verifyNoMoreInteractions(mailTransport);
        assertThat(redis.store).containsKeys("otp:registration:acme:" + pending, "otp:registration:acme:" + verified,
                "otp:registration:acme:" + unknown);
    }

    @Test
    void resendOtp_isThrottledPerEmail_withRetryAfter_identicallyForKnownAndUnknown() throws Exception {
        String known = "known" + SEQ.incrementAndGet() + "@x.test";
        String unknown = "ghost" + SEQ.incrementAndGet() + "@x.test";
        newUser(acme, known, "Oldpass1!acme", false, UserRole.CUSTOMER);
        for (String email : new String[]{known, unknown}) {
            for (int i = 0; i < 3; i++) {
                assertThat(resend(acme, email, "10.30." + SEQ.incrementAndGet() + ".1").getStatus()).isEqualTo(200);
            }
            MockHttpServletResponse blocked = resend(acme, email, "10.30." + SEQ.incrementAndGet() + ".1");
            assertThat(blocked.getStatus()).isEqualTo(429);
            assertThat(Long.parseLong(blocked.getHeader("Retry-After"))).isPositive();
        }
        // the same address on another tenant has its own budget
        assertThat(resend(bravo, known, "10.31.0.1").getStatus()).isEqualTo(200);
    }

    @Test
    void resendOtp_isThrottledPerIp_acrossManyEmails() throws Exception {
        String ip = "10.40.0.9";
        for (int i = 0; i < 6; i++) {
            assertThat(resend(acme, "n" + SEQ.incrementAndGet() + "@x.test", ip).getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse blocked = resend(acme, "n" + SEQ.incrementAndGet() + "@x.test", ip);
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isNotBlank();
        assertThat(resend(acme, "n" + SEQ.incrementAndGet() + "@x.test", "10.40.0.10").getStatus()).isEqualTo(200);
    }

    @Test
    void verifyOtp_isCappedAfterFiveWrongGuesses() throws Exception {
        String email = "cap" + SEQ.incrementAndGet() + "@x.test";
        newUser(acme, email, "Oldpass1!acme", false, UserRole.CUSTOMER);
        assertThat(resend(acme, email, "10.50.0.1").getStatus()).isEqualTo(200);
        String key = "otp:registration:acme:" + email;
        String code = (String) redis.store.get(key);
        String wrong = code.equals("111111") ? "222222" : "111111";
        for (int i = 0; i < 5; i++) {
            assertThat(call(with(post("/api/v1/auth/verify-otp"), ACME).contentType(JSON)
                    .content("{\"email\":\"" + email + "\",\"otp\":\"" + wrong + "\"}")).getStatus()).isEqualTo(400);
        }
        MockHttpServletResponse late = call(with(post("/api/v1/auth/verify-otp"), ACME).contentType(JSON)
                .content("{\"email\":\"" + email + "\",\"otp\":\"" + code + "\"}"));
        assertThat(late.getStatus()).as("the code is burned after the cap").isEqualTo(400);
    }

    // ------------------------------------------------------------------------------------------ AC4

    @Test
    void registration_enforcesThePasswordPolicy_andStoresTheStrippedPassword() throws Exception {
        String email = "pol" + SEQ.incrementAndGet() + "@x.test";
        MockHttpServletResponse weak = call(with(post("/api/v1/auth/register"), ACME).contentType(JSON).content(
                "{\"email\":\"" + email + "\",\"fullName\":\"P\",\"password\":\"weakpass\",\"confirmPassword\":\"weakpass\"}"));
        assertThat(weak.getStatus()).isEqualTo(400);
        assertThat(weak.getContentAsString()).contains("uppercase");
        MockHttpServletResponse tooLong = call(with(post("/api/v1/auth/register"), ACME).contentType(JSON).content(
                "{\"email\":\"" + email + "\",\"fullName\":\"P\",\"password\":\"Aa1!" + "x".repeat(70) + "\",\"confirmPassword\":\"x\"}"));
        assertThat(tooLong.getStatus()).isEqualTo(400);

        MockHttpServletResponse ok = call(with(post("/api/v1/auth/register"), ACME).contentType(JSON).content(
                "{\"email\":\"" + email + "\",\"fullName\":\"P\",\"password\":\"  Abcdef1!x \",\"confirmPassword\":\"Abcdef1!x\"}"));
        assertThat(ok.getStatus()).as(ok.getContentAsString()).isEqualTo(204);
        String hash = tenants.runAs(acme, () -> userRepository.findByEmail(email).orElseThrow().getPassword());
        assertThat(encoder.matches("Abcdef1!x", hash)).as("hash is of the stripped password").isTrue();
        assertThat(encoder.matches("  Abcdef1!x ", hash)).isFalse();
    }

    @Test
    void deliveryDetail_createWithoutLabelAndLandmark_succeedsWithHomeDefault() throws Exception {
        String email = "cust" + SEQ.incrementAndGet() + "@x.test";
        newUser(acme, email, "Custpass1!x", true, UserRole.CUSTOMER);
        MockHttpServletResponse in = login(acme, email, "Custpass1!x");
        assertThat(in.getStatus()).isEqualTo(200);
        Cookie[] session = {in.getCookie("accessToken"), in.getCookie("refreshToken")};

        MockHttpServletResponse r = call(with(post("/api/v1/delivery-detail/add-address"), ACME, session).contentType(JSON).content(
                "{\"recipientName\":\"R\",\"phoneNumber\":\"0200000000\",\"addressLine1\":\"1 Main St\",\"city\":\"Accra\",\"region\":\"GA\"}"));
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        assertThat(r.getContentAsString()).contains("\"label\":\"HOME\"").contains("\"landmark\":null");
    }
}
