package com.example.perfume_budget.tenantauth;

import com.example.perfume_budget.enums.AuthProvider;
import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.platform.auth.PlatformJwtService;
import com.example.perfume_budget.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API-RD-08 (customer self-service) end to end on the real filter chain and Postgres: {@code PATCH /auth/me} and
 * {@code POST /auth/change-password}. Covers wrong current password, the signup/reset password policy, reuse, REAL
 * token revocation (old access and refresh tokens are refused afterwards), the rate limit, the role boundaries
 * (CUSTOMER, ADMIN, FRONT_DESK own record only; anonymous, platform and other-tenant credentials refused), cross-tenant
 * isolation and a concurrent double change.
 */
class AccountSelfServiceIntegrationTest extends AbstractTenantWebTest {

    static final String PW = "Cust0mer!pass";
    static final String NEW_PW = "N3w-Passw0rd!";
    static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformJwtService platformJwt;

    private static String nextIp() {
        int n = SEQ.incrementAndGet();
        return "10.77." + (n / 250) + "." + (n % 250 + 1);
    }

    private String email(String prefix) {
        return prefix + SEQ.incrementAndGet() + "@acct.test";
    }

    private User newUser(String slug, String email, UserRole role, String password, AuthProvider provider) {
        var t = "acme".equals(slug) ? acme : bravo;
        return tenants.runAs(t, () -> users.save(User.builder().fullName("Original Name").email(email)
                .password(password == null ? "" : encoder.encode(password)).roles(role).isActive(true).emailVerified(true)
                .authProvider(provider).build()));
    }

    private User customer(String slug, String email) {
        return newUser(slug, email, UserRole.CUSTOMER, PW, AuthProvider.LOCAL);
    }

    private MockHttpServletResponse call(HttpMethod method, String path, String slug, Object body, String ip, Cookie... cookies)
            throws Exception {
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(method, path).header("X-Tenant-ID", slug)
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                });
        if (body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        if (cookies.length > 0) {
            b.cookie(cookies);
        }
        return mvc.perform(b).andReturn().getResponse();
    }

    private MockHttpServletResponse changePassword(String slug, String current, String next, String ip, Cookie... cookies)
            throws Exception {
        return call(HttpMethod.POST, "/api/v1/auth/change-password", slug,
                Map.of("currentPassword", current, "newPassword", next, "confirmPassword", next), ip, cookies);
    }

    private MockHttpServletResponse me(String slug, Cookie... cookies) throws Exception {
        return call(HttpMethod.GET, "/api/v1/auth/me", slug, null, nextIp(), cookies);
    }

    private record Session(Cookie access, Cookie refresh) {
        Cookie[] both() {
            return new Cookie[]{access, refresh};
        }
    }

    private Session login(String slug, String email, String password) throws Exception {
        MockHttpServletResponse r = call(HttpMethod.POST, "/api/v1/auth/login", slug,
                Map.of("email", email, "password", password), nextIp());
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        return new Session(cookie("accessToken", r.getCookie("accessToken").getValue()),
                cookie("refreshToken", r.getCookie("refreshToken").getValue()));
    }

    private int refresh(String slug, Cookie refreshCookie) throws Exception {
        return call(HttpMethod.POST, "/api/v1/auth/refresh", slug, null, nextIp(), refreshCookie).getStatus();
    }

    private int tokenVersion(String schema, String email) {
        return jdbc.queryForObject("SELECT token_version FROM " + schema + ".users WHERE email = ?", Integer.class, email);
    }

    private String hash(String schema, String email) {
        return jdbc.queryForObject("SELECT password FROM " + schema + ".users WHERE email = ?", String.class, email);
    }

    private String code(MockHttpServletResponse r) throws Exception {
        JsonNode n = json.readTree(r.getContentAsString());
        return n.at("/data/code").asText();
    }

    // ---------------------------------------------------------------- PATCH /auth/me

    @Test
    void patchMe_changesOnlyTheOwnFullName_trimmed_andMeShowsIt() throws Exception {
        String e = email("pm");
        customer("acme", e);
        String other = email("pm-other");
        customer("acme", other);
        Session s = login("acme", e, PW);

        MockHttpServletResponse r = call(HttpMethod.PATCH, "/api/v1/auth/me", "acme", Map.of("fullName", "  Ama Mensah  "),
                nextIp(), s.both());
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        JsonNode data = json.readTree(r.getContentAsString()).at("/data");
        assertThat(data.get("fullName").asText()).isEqualTo("Ama Mensah");
        assertThat(data.get("email").asText()).isEqualTo(e);
        assertThat(data.get("role").asText()).isEqualTo("CUSTOMER");
        assertThat(json.readTree(me("acme", s.both()).getContentAsString()).at("/data/fullName").asText()).isEqualTo("Ama Mensah");
        // another user in the same tenant is untouched
        assertThat(jdbc.queryForObject("SELECT full_name FROM tenant_acme.users WHERE email = ?", String.class, other))
                .isEqualTo("Original Name");
    }

    @Test
    void patchMe_ignoresEveryOtherField_noMassAssignment() throws Exception {
        String e = email("ma");
        customer("acme", e);
        Session s = login("acme", e, PW);
        MockHttpServletResponse r = call(HttpMethod.PATCH, "/api/v1/auth/me", "acme",
                Map.of("fullName", "Kofi", "roles", "ADMIN", "role", "ADMIN", "email", "evil@x.test", "isActive", false,
                        "password", "Hacked!Pass1", "tokenVersion", 99, "id", 1), nextIp(), s.both());
        assertThat(r.getStatus()).isEqualTo(200);
        var row = jdbc.queryForMap("SELECT roles, email, is_active, token_version, password FROM tenant_acme.users WHERE email = ?", e);
        assertThat(row.get("roles")).isEqualTo("CUSTOMER");
        assertThat(row.get("is_active")).isEqualTo(true);
        assertThat(row.get("token_version")).isEqualTo(0);
        assertThat(encoder.matches(PW, (String) row.get("password"))).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.users WHERE email = 'evil@x.test'", Integer.class)).isZero();
    }

    @Test
    void patchMe_validation_blank_tooLong_controlCharacters_are400() throws Exception {
        String e = email("pv");
        customer("acme", e);
        Session s = login("acme", e, PW);
        for (String bad : List.of("", "   ", "x".repeat(101), "Bad\u0000Name", "Line\nBreak")) {
            MockHttpServletResponse r = call(HttpMethod.PATCH, "/api/v1/auth/me", "acme", Map.of("fullName", bad), nextIp(), s.both());
            assertThat(r.getStatus()).as("name %s", bad.length()).isEqualTo(400);
        }
        assertThat(call(HttpMethod.PATCH, "/api/v1/auth/me", "acme", Map.of(), nextIp(), s.both()).getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT full_name FROM tenant_acme.users WHERE email = ?", String.class, e))
                .isEqualTo("Original Name");
    }

    // ---------------------------------------------------------------- change-password: current password, policy

    @Test
    void wrongCurrentPassword_is400_withCode_nothingChanges_noCookies() throws Exception {
        String e = email("wc");
        customer("acme", e);
        Session s = login("acme", e, PW);
        String before = hash("tenant_acme", e);

        MockHttpServletResponse r = changePassword("acme", "Not-My-Passw0rd!", NEW_PW, nextIp(), s.both());
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(code(r)).isEqualTo("CURRENT_PASSWORD_INCORRECT");
        assertThat(r.getHeaders("Set-Cookie")).isEmpty();
        assertThat(hash("tenant_acme", e)).isEqualTo(before);
        assertThat(tokenVersion("tenant_acme", e)).isZero();
        assertThat(me("acme", s.both()).getStatus()).as("session still valid").isEqualTo(200);
        // the (public) password policy is enforced by request validation, so a weak NEW password is a 400 with the
        // policy text whatever the current password is; it never reaches the password check and revokes nothing
        MockHttpServletResponse weak = changePassword("acme", PW, "weak", nextIp(), s.both());
        assertThat(weak.getStatus()).isEqualTo(400);
        assertThat(tokenVersion("tenant_acme", e)).isZero();
    }

    @Test
    void policyIsTheSignupAndResetPolicy_weakNewPasswordsAreRejected_andReuseToo() throws Exception {
        String e = email("pp");
        customer("acme", e);
        Session s = login("acme", e, PW);
        Map<String, String> weak = Map.of(
                "short", "Ab1!",
                "no upper", "n3w-passw0rd!",
                "no digit", "New-Password!",
                "no special", "NewPassw0rdX",
                "too long", "Aa1!" + "x".repeat(70));
        for (var en : weak.entrySet()) {
            MockHttpServletResponse r = changePassword("acme", PW, en.getValue(), nextIp(), s.both());
            assertThat(r.getStatus()).as(en.getKey()).isEqualTo(400);
        }
        // reuse of the current password, also when only surrounding whitespace differs (login strips it)
        MockHttpServletResponse same = changePassword("acme", PW, PW, nextIp(), s.both());
        assertThat(same.getStatus()).isEqualTo(400);
        assertThat(same.getContentAsString()).contains("New password cannot be same as current password");
        assertThat(changePassword("acme", PW, PW + "  ", nextIp(), s.both()).getStatus()).isEqualTo(400);
        // a mismatching confirmation
        MockHttpServletResponse mismatch = call(HttpMethod.POST, "/api/v1/auth/change-password", "acme",
                Map.of("currentPassword", PW, "newPassword", NEW_PW, "confirmPassword", NEW_PW + "x"), nextIp(), s.both());
        assertThat(mismatch.getStatus()).isEqualTo(400);
        // missing fields
        assertThat(call(HttpMethod.POST, "/api/v1/auth/change-password", "acme", Map.of("newPassword", NEW_PW), nextIp(), s.both())
                .getStatus()).isEqualTo(400);
        assertThat(tokenVersion("tenant_acme", e)).as("no rejected request revoked anything").isZero();
        assertThat(encoder.matches(PW, hash("tenant_acme", e))).isTrue();
    }

    @Test
    void confirmPasswordIsOptional() throws Exception {
        String e = email("co");
        customer("acme", e);
        Session s = login("acme", e, PW);
        MockHttpServletResponse r = call(HttpMethod.POST, "/api/v1/auth/change-password", "acme",
                Map.of("currentPassword", PW, "newPassword", NEW_PW), nextIp(), s.both());
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- change-password: revocation

    @Test
    void success_revokesEveryOlderToken_andReissuesTheCallersSession() throws Exception {
        String e = email("rv");
        customer("acme", e);
        Session phone = login("acme", e, PW);
        Session laptop = login("acme", e, PW);
        // a token minted before the tv claim existed (version 0 by definition) is valid before the change
        String legacy = tenants.runAs(acme, () -> jwtUtil.generateAccessToken(e, "CUSTOMER", 1L, "Original Name"));
        assertThat(me("acme", cookie("accessToken", legacy)).getStatus()).isEqualTo(200);
        assertThat(me("acme", laptop.both()).getStatus()).isEqualTo(200);

        MockHttpServletResponse r = changePassword("acme", PW, NEW_PW, nextIp(), phone.both());
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        assertThat(r.getContentAsString()).doesNotContain(PW).doesNotContain(NEW_PW);
        assertThat(tokenVersion("tenant_acme", e)).isEqualTo(1);

        // every older token is refused: access (this device and the other), refresh, and the legacy untagged one
        assertThat(me("acme", phone.access).getStatus()).as("old access, same device").isEqualTo(401);
        assertThat(me("acme", laptop.access).getStatus()).as("old access, other device").isEqualTo(401);
        assertThat(me("acme", cookie("accessToken", legacy)).getStatus()).as("legacy token").isEqualTo(401);
        assertThat(refresh("acme", phone.refresh)).as("old refresh, same device").isEqualTo(401);
        assertThat(refresh("acme", laptop.refresh)).as("old refresh, other device").isEqualTo(401);
        // a protected business route refuses the old token too (not only /me)
        assertThat(call(HttpMethod.GET, "/api/v1/cart", "acme", null, nextIp(), laptop.access).getStatus()).isEqualTo(401);

        // the caller's session continues on the re-issued cookies, which carry the new version
        Cookie newAccess = cookie("accessToken", r.getCookie("accessToken").getValue());
        Cookie newRefresh = cookie("refreshToken", r.getCookie("refreshToken").getValue());
        assertThat(jwtUtil.extractTokenVersion(newAccess.getValue())).isEqualTo(1);
        assertThat(jwtUtil.extractTokenVersion(newRefresh.getValue())).isEqualTo(1);
        assertThat(me("acme", newAccess).getStatus()).isEqualTo(200);
        assertThat(refresh("acme", newRefresh)).isEqualTo(200);

        // credentials: the old password is dead, the new one logs in, and a fresh login is at version 1
        MockHttpServletResponse oldLogin = call(HttpMethod.POST, "/api/v1/auth/login", "acme",
                Map.of("email", e, "password", PW), nextIp());
        assertThat(oldLogin.getStatus()).isIn(401, 403);
        Session fresh = login("acme", e, NEW_PW);
        assertThat(jwtUtil.extractTokenVersion(fresh.access.getValue())).isEqualTo(1);
        assertThat(me("acme", fresh.both()).getStatus()).isEqualTo(200);
    }

    @Test
    void aRefreshedAccessTokenCarriesTheCurrentVersion() throws Exception {
        String e = email("rf");
        customer("acme", e);
        Session s = login("acme", e, PW);
        MockHttpServletResponse ch = changePassword("acme", PW, NEW_PW, nextIp(), s.both());
        Cookie newRefresh = cookie("refreshToken", ch.getCookie("refreshToken").getValue());
        MockHttpServletResponse rr = call(HttpMethod.POST, "/api/v1/auth/refresh", "acme", null, nextIp(), newRefresh);
        assertThat(rr.getStatus()).isEqualTo(200);
        assertThat(jwtUtil.extractTokenVersion(rr.getCookie("accessToken").getValue())).isEqualTo(1);
    }

    @Test
    void patchMe_doesNotRevokeOrReissue() throws Exception {
        String e = email("pn");
        customer("acme", e);
        Session s = login("acme", e, PW);
        MockHttpServletResponse r = call(HttpMethod.PATCH, "/api/v1/auth/me", "acme", Map.of("fullName", "Yaa"), nextIp(), s.both());
        assertThat(r.getStatus()).isEqualTo(200);
        // an access token alone must not be exchangeable for a longer-lived session through this endpoint
        assertThat(r.getHeaders("Set-Cookie")).isEmpty();
        assertThat(tokenVersion("tenant_acme", e)).isZero();
        assertThat(me("acme", s.both()).getStatus()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- rate limit

    @Test
    void failuresFromOneAddressAreThrottled_theOwnerOnAnotherAddressIsNotLockedOut() throws Exception {
        String e = email("rl");
        customer("acme", e);
        Session s = login("acme", e, PW);
        String attacker = nextIp();
        for (int i = 0; i < 5; i++) {
            assertThat(changePassword("acme", "Guess-" + i + "-Passw0rd!", NEW_PW, attacker, s.both()).getStatus()).isEqualTo(400);
        }
        MockHttpServletResponse limited = changePassword("acme", "Guess-6-Passw0rd!", NEW_PW, attacker, s.both());
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isNotNull();
        assertThat(changePassword("acme", PW, NEW_PW, attacker, s.both()).getStatus()).as("same address, right password").isEqualTo(429);
        assertThat(encoder.matches(PW, hash("tenant_acme", e))).isTrue();
        // the attacker's failures did not spend the owner's budget: the owner's own address succeeds
        assertThat(changePassword("acme", PW, NEW_PW, nextIp(), s.both()).getStatus()).isEqualTo(200);
        // another user (same tenant, same attacker address) has an independent budget
        String e2 = email("rl2");
        customer("acme", e2);
        Session s2 = login("acme", e2, PW);
        assertThat(changePassword("acme", "Nope-Passw0rd!1", NEW_PW, attacker, s2.both()).getStatus()).isEqualTo(400);
    }

    @Test
    void perUserCeiling_boundsGuessingFromManyAddresses_andForgotPasswordStillRecovers() throws Exception {
        String e = email("ce");
        customer("acme", e);
        Session s = login("acme", e, PW);
        for (int ipN = 0; ipN < 6; ipN++) {
            String ip = nextIp();
            for (int i = 0; i < 5; i++) {
                assertThat(changePassword("acme", "Guess-" + ipN + i + "-Pw0rd!", NEW_PW, ip, s.both()).getStatus()).isEqualTo(400);
            }
        }
        // 30 failures from six addresses used the ceiling: even the right password from a fresh address is refused
        assertThat(changePassword("acme", PW, NEW_PW, nextIp(), s.both()).getStatus()).isEqualTo(429);
        // recovery path: forgot-password then reset still works and revokes the thief's session
        resetPasswordViaToken("acme", e, NEW_PW);
        assertThat(me("acme", s.access).getStatus()).isEqualTo(401);
        assertThat(refresh("acme", s.refresh)).isEqualTo(401);
        login("acme", e, NEW_PW);
    }

    @Test
    void successfulChangesDoNotConsumeTheUserBudget() throws Exception {
        String e = email("sc");
        customer("acme", e);
        Session s = login("acme", e, PW);
        String ip = nextIp();
        String cur = PW;
        Cookie[] cookies = s.both();
        for (int i = 0; i < 8; i++) { // more than the 5-failure cap
            String next = "Rot4te-Pass-" + i + "!";
            MockHttpServletResponse r = changePassword("acme", cur, next, ip, cookies);
            assertThat(r.getStatus()).as("change #" + i).isEqualTo(200);
            cookies = new Cookie[]{cookie("accessToken", r.getCookie("accessToken").getValue()),
                    cookie("refreshToken", r.getCookie("refreshToken").getValue())};
            cur = next;
        }
        assertThat(tokenVersion("tenant_acme", e)).isEqualTo(8);
    }

    @Test
    void theUserBudgetIsPerTenant_sameUserIdAndAddressInAcmeAndBravoDoNotShareABucket() throws Exception {
        String e = email("tb");
        User a = customer("acme", e);
        User b = customer("bravo", e);
        if (!a.getId().equals(b.getId())) {
            jdbc.update("UPDATE tenant_bravo.users SET id = ? WHERE email = ?", a.getId(), e);
        }
        Session sa = login("acme", e, PW);
        Session sb = login("bravo", e, PW);
        String ip = nextIp();
        for (int i = 0; i < 5; i++) {
            assertThat(changePassword("acme", "Guess-" + i + "-Passw0rd!", NEW_PW, ip, sa.both()).getStatus()).isEqualTo(400);
        }
        assertThat(changePassword("acme", "Guess-x-Passw0rd!", NEW_PW, ip, sa.both()).getStatus()).isEqualTo(429);
        assertThat(changePassword("bravo", "Guess-y-Passw0rd!", NEW_PW, ip, sb.both()).getStatus()).as("bravo has its own bucket").isEqualTo(400);
    }

    // ---------------------------------------------------------------- password reset revokes too

    /** forgot-password, then reset-password with the token the (in-memory) store holds for this e-mail. */
    private void resetPasswordViaToken(String slug, String email, String newPassword) throws Exception {
        assertThat(call(HttpMethod.POST, "/api/v1/auth/forgot-password", slug, Map.of("email", email), nextIp()).getStatus()).isEqualTo(200);
        String token = redis.entrySet().stream().filter(en -> en.getKey().contains("reset") && email.equals(en.getValue()))
                .map(Map.Entry::getKey).map(k -> k.substring(k.lastIndexOf(':') + 1)).findFirst().orElseThrow();
        MockHttpServletResponse r = call(HttpMethod.POST, "/api/v1/auth/reset-password?resetPasswordToken=" + token, slug,
                Map.of("password", newPassword, "confirmPassword", newPassword), nextIp());
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    }

    @Test
    void passwordReset_revokesEveryOlderAccessAndRefreshToken() throws Exception {
        String e = email("rs");
        customer("acme", e);
        Session phone = login("acme", e, PW);
        Session laptop = login("acme", e, PW);
        String legacy = tenants.runAs(acme, () -> jwtUtil.generateAccessToken(e, "CUSTOMER", 1L, "Original Name"));
        assertThat(me("acme", laptop.both()).getStatus()).isEqualTo(200);

        resetPasswordViaToken("acme", e, NEW_PW);

        assertThat(tokenVersion("tenant_acme", e)).isEqualTo(1);
        assertThat(me("acme", phone.access).getStatus()).isEqualTo(401);
        assertThat(me("acme", laptop.access).getStatus()).isEqualTo(401);
        assertThat(me("acme", cookie("accessToken", legacy)).getStatus()).isEqualTo(401);
        assertThat(refresh("acme", phone.refresh)).isEqualTo(401);
        assertThat(refresh("acme", laptop.refresh)).isEqualTo(401);
        Session fresh = login("acme", e, NEW_PW);
        assertThat(me("acme", fresh.both()).getStatus()).isEqualTo(200);
        // same e-mail in bravo: untouched
    }

    @Test
    void currentPasswordIsComparedStripped_likeLogin() throws Exception {
        String e = email("st");
        customer("acme", e);
        Session s = login("acme", e, PW);
        MockHttpServletResponse r = changePassword("acme", "  " + PW + "  ", NEW_PW, nextIp(), s.both());
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    }

    @Test
    void changePasswordIsRateLimitedPerIp() throws Exception {
        String ip = nextIp();
        int limited = 0;
        for (int i = 0; i < 22; i++) {
            String e = email("ip");
            customer("acme", e);
            Session s = login("acme", e, PW);
            int st = changePassword("acme", "Nope-Passw0rd!1", NEW_PW, ip, s.both()).getStatus();
            if (st == 429) {
                limited++;
            } else {
                assertThat(st).isEqualTo(400);
            }
        }
        // 20 per IP per 10 minutes (continuous refill may grant one more token while the test runs)
        assertThat(limited).as("the 21st and 22nd attempt from one IP").isBetween(1, 2);
    }

    // ---------------------------------------------------------------- role boundaries

    @Test
    void anonymousPlatformAndOtherTenantCredentialsAreRefused() throws Exception {
        String e = email("rb");
        customer("acme", e);
        Session s = login("acme", e, PW);
        Map<String, String> pw = Map.of("currentPassword", PW, "newPassword", NEW_PW);
        // anonymous
        assertThat(call(HttpMethod.POST, "/api/v1/auth/change-password", "acme", pw, nextIp()).getStatus()).isEqualTo(401);
        assertThat(call(HttpMethod.PATCH, "/api/v1/auth/me", "acme", Map.of("fullName", "X"), nextIp()).getStatus()).isEqualTo(401);
        // a platform super-admin token is not a tenant credential (separate cookie, separate key)
        String platformToken = platformJwt.issueAccess(1L, "root@platform.test", "Root");
        Cookie platformCookie = cookie("platformAccessToken", platformToken);
        Cookie asTenantCookie = cookie("accessToken", platformToken);
        assertThat(call(HttpMethod.POST, "/api/v1/auth/change-password", "acme", pw, nextIp(), platformCookie).getStatus()).isEqualTo(401);
        assertThat(call(HttpMethod.POST, "/api/v1/auth/change-password", "acme", pw, nextIp(), asTenantCookie).getStatus()).isEqualTo(401);
        assertThat(call(HttpMethod.PATCH, "/api/v1/auth/me", "acme", Map.of("fullName", "X"), nextIp(), asTenantCookie).getStatus()).isEqualTo(401);
        // a refresh token is not an access token
        assertThat(call(HttpMethod.POST, "/api/v1/auth/change-password", "acme", pw, nextIp(),
                cookie("accessToken", s.refresh.getValue())).getStatus()).isEqualTo(401);
        // an acme token used on bravo
        assertThat(call(HttpMethod.POST, "/api/v1/auth/change-password", "bravo", pw, nextIp(), s.access).getStatus()).isEqualTo(401);
        assertThat(call(HttpMethod.PATCH, "/api/v1/auth/me", "bravo", Map.of("fullName", "X"), nextIp(), s.access).getStatus()).isEqualTo(401);
        assertThat(encoder.matches(PW, hash("tenant_acme", e))).isTrue();
        assertThat(tokenVersion("tenant_acme", e)).isZero();
    }

    @Test
    void customerAdminAndFrontDeskEachChangeOnlyTheirOwnRecord() throws Exception {
        String c = email("c"), f = email("f"), a = email("a");
        customer("acme", c);
        newUser("acme", f, UserRole.FRONT_DESK, PW, AuthProvider.LOCAL);
        newUser("acme", a, UserRole.ADMIN, PW, AuthProvider.LOCAL);
        for (String who : List.of(c, f, a)) {
            Session s = login("acme", who, PW);
            MockHttpServletResponse r = changePassword("acme", PW, NEW_PW, nextIp(), s.both());
            assertThat(r.getStatus()).as(who).isEqualTo(200);
        }
        for (String who : List.of(c, f, a)) {
            assertThat(tokenVersion("tenant_acme", who)).as(who).isEqualTo(1);
            assertThat(encoder.matches(NEW_PW, hash("tenant_acme", who))).as(who).isTrue();
        }
        // the seeded tenant admin was never touched
        assertThat(tokenVersion("tenant_acme", acme.adminEmail())).isZero();
        assertThat(encoder.matches(com.example.perfume_budget.support.TestTenants.ADMIN_PASSWORD,
                hash("tenant_acme", acme.adminEmail()))).isTrue();
    }

    @Test
    void passwordlessGoogleAccount_isTold400WithItsOwnCode() throws Exception {
        String e = email("g");
        newUser("acme", e, UserRole.CUSTOMER, null, AuthProvider.GOOGLE);
        String token = tenants.runAs(acme, () -> jwtUtil.generateAccessToken(e, "CUSTOMER", 1L, "G"));
        MockHttpServletResponse r = changePassword("acme", "anything", NEW_PW, nextIp(), cookie("accessToken", token));
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(code(r)).isEqualTo("NO_LOCAL_PASSWORD");
        assertThat(tokenVersion("tenant_acme", e)).isZero();
    }

    // ---------------------------------------------------------------- cross-tenant isolation

    @Test
    void sameEmailInTwoTenants_changeInAcmeNeverTouchesBravo() throws Exception {
        String e = email("xt");
        customer("acme", e);
        customer("bravo", e);
        Session acmeS = login("acme", e, PW);
        Session bravoS = login("bravo", e, PW);
        String bravoHash = hash("tenant_bravo", e);

        assertThat(changePassword("acme", PW, NEW_PW, nextIp(), acmeS.both()).getStatus()).isEqualTo(200);
        assertThat(call(HttpMethod.PATCH, "/api/v1/auth/me", "acme", Map.of("fullName", "Acme Only"), nextIp(),
                login("acme", e, NEW_PW).both()).getStatus()).isEqualTo(200);

        assertThat(me("acme", acmeS.access).getStatus()).isEqualTo(401);
        // bravo: same email, same password, its own tokens still valid, row unchanged
        assertThat(me("bravo", bravoS.both()).getStatus()).isEqualTo(200);
        assertThat(refresh("bravo", bravoS.refresh)).isEqualTo(200);
        assertThat(tokenVersion("tenant_bravo", e)).isZero();
        assertThat(hash("tenant_bravo", e)).isEqualTo(bravoHash);
        assertThat(jdbc.queryForObject("SELECT full_name FROM tenant_bravo.users WHERE email = ?", String.class, e))
                .isEqualTo("Original Name");
        login("bravo", e, PW); // bravo still accepts the OLD password
    }

    @Test
    void anAcmeTokenOfACommonEmailCannotReachBravosAccountRoutes() throws Exception {
        String e = email("xt2");
        customer("acme", e);
        customer("bravo", e);
        Session acmeS = login("acme", e, PW);
        assertThat(changePassword("bravo", PW, NEW_PW, nextIp(), acmeS.both()).getStatus()).isEqualTo(401);
        assertThat(tokenVersion("tenant_bravo", e)).isZero();
        assertThat(encoder.matches(PW, hash("tenant_bravo", e))).isTrue();
    }

    // ---------------------------------------------------------------- concurrency

    @Test
    void twoConcurrentChanges_serialise_exactlyOneWins_versionBumpedOnce() throws Exception {
        String e = email("cc");
        customer("acme", e);
        Session s = login("acme", e, PW);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            String[] next = {"First-Passw0rd!", "Second-Passw0rd!"};
            List<Future<Integer>> results = new java.util.ArrayList<>();
            for (String n : next) {
                Callable<Integer> task = () -> {
                    go.await();
                    return changePassword("acme", PW, n, nextIp(), s.both()).getStatus();
                };
                results.add(pool.submit(task));
            }
            go.countDown();
            List<Integer> statuses = new java.util.ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get(60, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 400);
        } finally {
            pool.shutdownNow();
        }
        assertThat(tokenVersion("tenant_acme", e)).isEqualTo(1);
    }
}
