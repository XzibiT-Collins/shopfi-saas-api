package com.example.perfume_budget.tenantauth;

import com.example.perfume_budget.enums.AuthProvider;
import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.notification.MailMessage;
import com.example.perfume_budget.notification.MailTransport;
import com.example.perfume_budget.repository.UserRepository;
import com.example.perfume_budget.support.TestTenant;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * DOMAIN-FD part 2: the FRONT_DESK login is the ADMIN login. The admin shell signs in through the one tenant login
 * ({@code POST /auth/login} on the tenant host, the {@code verify-login-otp} step, forgot/reset, {@code GET /auth/me});
 * the role decides what the session may call. These tests pin that on the real filter chain and Postgres, with the
 * tenant resolved from the browser Origin under a base domain that is NOT any default (tenant.base-domain below), so
 * they also prove the configured domain drives resolution, CORS and the e-mailed link.
 */
@TestPropertySource(properties = {
        "tenant.base-domain=desk.example.test",
        "application.auth.rate-limit.forgot-password-ip.capacity=1000",
        "application.auth.rate-limit.forgot-password-email.capacity=1000"
})
class FrontDeskAdminLoginIntegrationTest extends AbstractTenantWebTest {

    static final String BASE = "desk.example.test";
    static final String ACME = "https://acme." + BASE;
    static final String BRAVO = "https://bravo." + BASE;
    static final String PW = "Fr0nt-Desk!pw";
    static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @MockitoBean MailTransport mailTransport;

    @BeforeEach
    void clearMail() {
        Mockito.clearInvocations(mailTransport);
    }

    @AfterEach
    void flagsAndTemplateOff() {
        for (String schema : new String[]{"tenant_acme", "tenant_bravo"}) {
            jdbc.update("UPDATE " + schema + ".feature_flags SET enabled = false");
            jdbc.update("DELETE FROM " + schema + ".front_desk_user_permission_overrides");
            jdbc.update("DELETE FROM " + schema + ".front_desk_permission_templates");
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private MockHttpServletResponse call(HttpMethod m, String path, String origin, Object body, Cookie... cookies)
            throws Exception {
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(m, path).header("Origin", origin)
                .with(r -> {
                    r.setRemoteAddr("10.88.0." + (SEQ.incrementAndGet() % 250 + 1));
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

    private MockHttpServletResponse login(String origin, String email, String password) throws Exception {
        return call(HttpMethod.POST, "/api/v1/auth/login", origin, Map.of("email", email, "password", password));
    }

    private Cookie[] session(MockHttpServletResponse r) throws Exception {
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        return new Cookie[]{r.getCookie("accessToken"), r.getCookie("refreshToken")};
    }

    private JsonNode data(MockHttpServletResponse r) throws Exception {
        return json.readTree(r.getContentAsString()).at("/data");
    }

    private User newUser(TestTenant t, String email, UserRole role, boolean active, boolean verified) {
        return tenants.runAs(t, () -> users.save(User.builder().fullName("Staff Member").email(email)
                .password(encoder.encode(PW)).roles(role).isActive(active).emailVerified(verified)
                .authProvider(AuthProvider.LOCAL).build()));
    }

    private String email(String p) {
        return p + SEQ.incrementAndGet() + "@desk.test";
    }

    private void setTemplate(TestTenant t, String origin, String... permissions) throws Exception {
        Cookie[] admin = session(login(origin, t.adminEmail(), t.adminPassword()));
        MockHttpServletResponse r = call(HttpMethod.PUT, "/api/v1/admin/front-desk/template", origin,
                Map.of("permissions", List.of(permissions)), admin);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    }

    // ------------------------------------------------------------------------------------------------ sign-in

    @Test
    void adminAndFrontDesk_signInThroughTheSameEndpoint_andGetRoleAndPermissions() throws Exception {
        setTemplate(acme, ACME, "WALK_IN_ORDER_CREATE", "CUSTOMER_SEARCH");
        // the front-desk account is created the real way: by the admin in Settings -> Team
        Cookie[] admin = session(login(ACME, acme.adminEmail(), acme.adminPassword()));
        String desk = email("desk");
        MockHttpServletResponse created = call(HttpMethod.POST, "/api/v1/admin/front-desk/users", ACME,
                Map.of("fullName", "Ama Desk", "email", desk, "password", PW, "isActive", true), admin);
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(200);

        MockHttpServletResponse a = login(ACME, acme.adminEmail(), acme.adminPassword());
        MockHttpServletResponse f = login(ACME, desk, PW);
        for (MockHttpServletResponse r : List.of(a, f)) {
            assertThat(r.getStatus()).isEqualTo(200);
            assertThat(data(r).at("/requiresOtp").asBoolean()).isFalse();
            assertThat(jwtUtil.extractTenant(r.getCookie("accessToken").getValue())).isEqualTo("acme");
            assertThat(jwtUtil.extractTenant(r.getCookie("refreshToken").getValue())).isEqualTo("acme");
        }
        assertThat(data(a).at("/auth/role").asText()).isEqualTo("ADMIN");
        assertThat(data(a).at("/auth/permissions")).isEmpty();
        assertThat(data(f).at("/auth/role").asText()).isEqualTo("FRONT_DESK");
        assertThat(data(f).at("/auth/email").asText()).isEqualTo(desk);
        assertThat(json.convertValue(data(f).at("/auth/permissions"), List.class))
                .containsExactlyInAnyOrder("WALK_IN_ORDER_CREATE", "CUSTOMER_SEARCH");

        // /auth/me answers the shell the same object for the front-desk session
        MockHttpServletResponse me = call(HttpMethod.GET, "/api/v1/auth/me", ACME, null, session(f));
        assertThat(me.getStatus()).isEqualTo(200);
        assertThat(data(me).at("/role").asText()).isEqualTo("FRONT_DESK");
        assertThat(json.convertValue(data(me).at("/permissions"), List.class))
                .containsExactlyInAnyOrder("WALK_IN_ORDER_CREATE", "CUSTOMER_SEARCH");
    }

    @Test
    void permissionsAreReadLive_notFrozenInTheToken() throws Exception {
        String desk = email("live");
        newUser(acme, desk, UserRole.FRONT_DESK, true, true);
        Cookie[] s = session(login(ACME, desk, PW));
        assertThat(data(call(HttpMethod.GET, "/api/v1/auth/me", ACME, null, s)).at("/permissions")).isEmpty();
        setTemplate(acme, ACME, "STOCK_TRANSFER");
        assertThat(json.convertValue(data(call(HttpMethod.GET, "/api/v1/auth/me", ACME, null, s)).at("/permissions"), List.class))
                .containsExactly("STOCK_TRANSFER");
    }

    @Test
    void frontDeskLoginOtpStep_isPerRole_andIssuesSessionOnlyAfterTheCode() throws Exception {
        String desk = email("otp");
        newUser(acme, desk, UserRole.FRONT_DESK, true, true);
        jdbc.update("UPDATE tenant_acme.feature_flags SET enabled = true WHERE feature_key = 'LOGIN_OTP' AND audience = 'FRONT_DESK'");

        // admin is not affected by the front-desk switch: direct sign-in
        assertThat(data(login(ACME, acme.adminEmail(), acme.adminPassword())).at("/requiresOtp").asBoolean()).isFalse();

        MockHttpServletResponse step1 = login(ACME, desk, PW);
        assertThat(step1.getStatus()).isEqualTo(200);
        assertThat(data(step1).at("/requiresOtp").asBoolean()).isTrue();
        assertThat(step1.getCookie("accessToken")).as("no session before the code").isNull();
        assertThat(data(step1).at("/auth").isNull() || data(step1).at("/auth").isMissingNode()).isTrue();
        String challenge = data(step1).at("/challengeToken").asText();
        String otp = redis.entrySet().stream().filter(e -> e.getKey().startsWith("otp:login:code:acme:")
                && e.getKey().endsWith(challenge)).map(e -> (String) e.getValue()).findFirst().orElseThrow();

        MockHttpServletResponse bad = call(HttpMethod.POST, "/api/v1/auth/verify-login-otp", ACME,
                Map.of("challengeToken", challenge, "otp", otp.equals("000000") ? "111111" : "000000"));
        assertThat(bad.getStatus()).isBetween(400, 499);
        assertThat(bad.getCookie("accessToken")).isNull();

        // the same challenge on another tenant's origin is refused
        MockHttpServletResponse cross = call(HttpMethod.POST, "/api/v1/auth/verify-login-otp", BRAVO,
                Map.of("challengeToken", challenge, "otp", otp));
        assertThat(cross.getStatus()).isBetween(400, 499);
        assertThat(cross.getCookie("accessToken")).isNull();

        MockHttpServletResponse step2 = call(HttpMethod.POST, "/api/v1/auth/verify-login-otp", ACME,
                Map.of("challengeToken", challenge, "otp", otp));
        assertThat(step2.getStatus()).as(step2.getContentAsString()).isEqualTo(200);
        assertThat(data(step2).at("/role").asText()).isEqualTo("FRONT_DESK");
        assertThat(jwtUtil.extractTenant(step2.getCookie("accessToken").getValue())).isEqualTo("acme");
    }

    private static final Pattern LINK = Pattern.compile(
            "https://([a-z0-9-]+)\\.desk\\.example\\.test/reset-password\\?resetPasswordToken=([A-Za-z0-9_-]+)");

    @Test
    void frontDeskForgotAndReset_linkIsOnTheConfiguredDomain_andRevokesTheOldSession() throws Exception {
        String desk = email("reset");
        newUser(acme, desk, UserRole.FRONT_DESK, true, true);
        // this fixture has no verified custom domain, so the link must come from tenant.base-domain
        jdbc.update("UPDATE master.tenants SET custom_domain = NULL, custom_domain_status = NULL, custom_domain_token = NULL WHERE slug = 'acme'");
        Cookie[] old = session(login(ACME, desk, PW));

        MockHttpServletResponse f = call(HttpMethod.POST, "/api/v1/auth/forgot-password", ACME, Map.of("email", desk));
        assertThat(f.getStatus()).isEqualTo(200);
        ArgumentCaptor<MailMessage> mail = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailTransport, timeout(8000)).send(mail.capture());
        Matcher m = LINK.matcher(mail.getValue().html());
        assertThat(m.find()).as("reset link on tenant.base-domain").isTrue();
        assertThat(m.group(1)).isEqualTo("acme");
        assertThat(mail.getValue().html()).doesNotContain("shopfi.test");

        String next = "N3w-Desk!pass";
        MockHttpServletRequestBuilder reset = MockMvcRequestBuilders.post("/api/v1/auth/reset-password")
                .queryParam("resetPasswordToken", m.group(2)).header("Origin", ACME)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("password", next, "confirmPassword", next)));
        assertThat(mvc.perform(reset).andReturn().getResponse().getStatus()).isEqualTo(200);

        assertThat(login(ACME, desk, PW).getStatus()).isEqualTo(401);
        MockHttpServletResponse again = login(ACME, desk, next);
        assertThat(data(again).at("/auth/role").asText()).isEqualTo("FRONT_DESK");
        assertThat(call(HttpMethod.GET, "/api/v1/auth/me", ACME, null, old).getStatus())
                .as("the pre-reset session is revoked").isEqualTo(401);

        // unknown address: same answer, no mail (no enumeration)
        Mockito.clearInvocations(mailTransport);
        assertThat(call(HttpMethod.POST, "/api/v1/auth/forgot-password", ACME, Map.of("email", email("ghost"))).getStatus())
                .isEqualTo(200);
        Mockito.verifyNoInteractions(mailTransport);
    }

    // ------------------------------------------------------------------------------------------------ who may sign in

    @Test
    void inactiveOrUnverifiedFrontDesk_cannotSignIn_andDeactivationStopsTheLiveSession() throws Exception {
        String off = email("off");
        newUser(acme, off, UserRole.FRONT_DESK, false, true);
        MockHttpServletResponse r1 = login(ACME, off, PW);
        assertThat(r1.getStatus()).isIn(401, 403);
        assertThat(r1.getCookie("accessToken")).isNull();
        String unv = email("unv");
        newUser(acme, unv, UserRole.FRONT_DESK, true, false);
        MockHttpServletResponse r2 = login(ACME, unv, PW);
        assertThat(r2.getStatus()).isIn(401, 403);
        assertThat(r2.getCookie("accessToken")).isNull();

        String live = email("live");
        User u = newUser(acme, live, UserRole.FRONT_DESK, true, true);
        Cookie[] s = session(login(ACME, live, PW));
        Cookie[] admin = session(login(ACME, acme.adminEmail(), acme.adminPassword()));
        assertThat(call(HttpMethod.PATCH, "/api/v1/admin/front-desk/users/" + u.getId(), ACME,
                Map.of("isActive", false), admin).getStatus()).isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/api/v1/auth/me", ACME, null, s).getStatus()).isEqualTo(401);
    }

    @Test
    void wrongPasswordUnknownUserAndOtherTenant_areRefusedAlike() throws Exception {
        String desk = email("x");
        newUser(acme, desk, UserRole.FRONT_DESK, true, true);
        MockHttpServletResponse wrong = login(ACME, desk, PW + "x");
        MockHttpServletResponse unknown = login(ACME, email("nobody"), PW);
        MockHttpServletResponse other = login(BRAVO, desk, PW);   // exists on acme only
        assertThat(wrong.getStatus()).isEqualTo(401);
        assertThat(unknown.getStatus()).isEqualTo(401);
        assertThat(other.getStatus()).isEqualTo(401);
        assertThat(wrong.getContentAsString()).isEqualTo(unknown.getContentAsString());
        assertThat(other.getCookie("accessToken")).isNull();
        // acme front-desk cookies replayed on bravo
        Cookie[] s = session(login(ACME, desk, PW));
        assertThat(call(HttpMethod.GET, "/api/v1/auth/me", BRAVO, null, s).getStatus()).isEqualTo(401);
    }

    /**
     * The tenant login is shared with the storefront, so today a CUSTOMER can sign in on it (that is how shoppers log
     * in). The rule that matters is enforced per endpoint: the role of the session. A CUSTOMER session gets nothing
     * under /admin; the shell must read {@code role} and refuse CUSTOMER itself.
     */
    @Test
    void customerSignsInOnTheSharedLogin_butHasNoAdminAccess_andFrontDeskHasOnlyItsRoutes() throws Exception {
        String cust = email("cust");
        newUser(acme, cust, UserRole.CUSTOMER, true, true);
        String desk = email("fd");
        newUser(acme, desk, UserRole.FRONT_DESK, true, true);
        MockHttpServletResponse c = login(ACME, cust, PW);
        assertThat(data(c).at("/auth/role").asText()).isEqualTo("CUSTOMER");
        assertThat(data(c).at("/auth/permissions")).isEmpty();
        Cookie[] cs = session(c);
        Cookie[] fs = session(login(ACME, desk, PW));
        Cookie[] as = session(login(ACME, acme.adminEmail(), acme.adminPassword()));

        // ADMIN-only on every layer: front desk and customer get 403, anonymous 401
        List<String[]> adminOnly = List.of(
                new String[]{"GET", "/api/v1/admin/front-desk/users"},
                new String[]{"GET", "/api/v1/admin/front-desk/template"},
                new String[]{"PATCH", "/api/v1/admin/front-desk/users/1/assign"},
                new String[]{"GET", "/api/v1/admin/product-families"},
                new String[]{"GET", "/api/v1/admin/inventory/legacy-balance-report"},
                new String[]{"POST", "/api/v1/admin/walk-in/orders/WI-1/cancel"},
                new String[]{"POST", "/api/v1/admin/walk-in/orders/WI-1/refund"},
                new String[]{"GET", "/api/v1/product/admin/listing"});
        for (String[] e : adminOnly) {
            HttpMethod m = HttpMethod.valueOf(e[0]);
            Object body = m == HttpMethod.GET ? null : Map.of("reason", "x");
            assertThat(call(m, e[1], ACME, body, fs).getStatus()).as("FRONT_DESK %s %s", e[0], e[1]).isEqualTo(403);
            assertThat(call(m, e[1], ACME, body, cs).getStatus()).as("CUSTOMER %s %s", e[0], e[1]).isEqualTo(403);
            assertThat(call(m, e[1], ACME, body).getStatus()).as("anonymous %s %s", e[0], e[1]).isEqualTo(401);
        }
        assertThat(call(HttpMethod.GET, "/api/v1/admin/front-desk/users", ACME, null, as).getStatus()).isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/api/v1/admin/product-families", ACME, null, as).getStatus()).isEqualTo(200);

        // front-desk routes: role gate lets FRONT_DESK in, the permission gate decides; CUSTOMER never
        assertThat(call(HttpMethod.GET, "/api/v1/admin/walk-in/orders", ACME, null, cs).getStatus()).isEqualTo(403);
        assertThat(call(HttpMethod.GET, "/api/v1/admin/notifications", ACME, null, cs).getStatus()).isEqualTo(403);
        assertThat(call(HttpMethod.GET, "/api/v1/admin/walk-in/orders", ACME, null, fs).getStatus())
                .as("empty template: no WALK_IN_ORDER_VIEW").isEqualTo(403);
        assertThat(call(HttpMethod.GET, "/api/v1/admin/notifications", ACME, null, fs).getStatus()).isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/api/v1/admin/inventory/locations", ACME, null, fs).getStatus()).isEqualTo(200);
        setTemplate(acme, ACME, "WALK_IN_ORDER_VIEW");
        assertThat(call(HttpMethod.GET, "/api/v1/admin/walk-in/orders", ACME, null, fs).getStatus())
                .as("granted in Settings -> Team, effective on the very next request").isEqualTo(200);
    }

    @Test
    void frontDeskCannotEscalate_ownRoleOrAnotherAccount() throws Exception {
        String desk = email("esc");
        User me = newUser(acme, desk, UserRole.FRONT_DESK, true, true);
        Cookie[] s = session(login(ACME, desk, PW));

        // extra fields on the self-service call are not a way to change the role
        call(HttpMethod.PATCH, "/api/v1/auth/me", ACME, Map.of("fullName", "Renamed", "role", "ADMIN"), s);
        assertThat(tenants.runAs(acme, () -> users.findById(me.getId()).orElseThrow().getRoles()))
                .isEqualTo(UserRole.FRONT_DESK);
        // nor the admin staff endpoints
        assertThat(call(HttpMethod.PATCH, "/api/v1/admin/front-desk/users/" + me.getId() + "/assign", ACME, Map.of(), s)
                .getStatus()).isEqualTo(403);
        assertThat(call(HttpMethod.PUT, "/api/v1/admin/front-desk/users/" + me.getId() + "/permissions", ACME,
                Map.of("allowedPermissions", List.of("STOCK_TRANSFER"), "deniedPermissions", List.of()), s).getStatus())
                .isEqualTo(403);
        assertThat(call(HttpMethod.PUT, "/api/v1/admin/front-desk/template", ACME,
                Map.of("permissions", List.of("STOCK_TRANSFER")), s).getStatus()).isEqualTo(403);
        assertThat(data(call(HttpMethod.GET, "/api/v1/auth/me", ACME, null, s)).at("/role").asText()).isEqualTo("FRONT_DESK");
        assertThat(data(call(HttpMethod.GET, "/api/v1/auth/me", ACME, null, s)).at("/permissions")).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------ configured domain

    @Test
    void cors_followsTheConfiguredBaseDomain_forTheAdminLogin() throws Exception {
        MockHttpServletResponse ok = mvc.perform(MockMvcRequestBuilders.options("/api/v1/auth/login")
                .header("Origin", ACME).header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type")).andReturn().getResponse();
        assertThat(ok.getStatus()).isEqualTo(200);
        assertThat(ok.getHeader("Access-Control-Allow-Origin")).isEqualTo(ACME);
        assertThat(ok.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
        for (String foreign : List.of("https://acme.shopfi.test", "https://acme.shop.uniitflow.com", "https://acme.example.com",
                "https://" + BASE, "https://x.acme." + BASE)) {
            MockHttpServletResponse no = mvc.perform(MockMvcRequestBuilders.options("/api/v1/auth/login")
                    .header("Origin", foreign).header("Access-Control-Request-Method", "POST")).andReturn().getResponse();
            assertThat(no.getStatus()).as(foreign).isEqualTo(403);
            assertThat(no.getHeader("Access-Control-Allow-Origin")).as(foreign).isNull();
        }
    }
}
