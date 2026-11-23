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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * API-RD-07 AC1 end to end: {@code POST /platform/auth/change-password}. Wrong current password, policy, reuse,
 * REAL revocation (old access and refresh tokens refused afterwards, other admins unaffected), rate limit, role
 * boundaries (anonymous, CUSTOMER / ADMIN / FRONT_DESK tenant credentials), concurrency.
 */
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "tenant.base-domain=shopfi.test",
        "platform.cors.allowed-origins=https://console.shopfi.example",
        "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=0",
        "platform.auth.login-ip.capacity=1000",
        "platform.auth.login-email.capacity=1000",
        "platform.auth.refresh-ip.capacity=1000",
        "platform.auth.change-password-ip.capacity=6",
        "platform.auth.change-password-ip.period-seconds=3600",
        "platform.auth.change-password-admin.capacity=12",
        "platform.auth.change-password-admin.period-seconds=3600"
})
class PlatformChangePasswordIntegrationTest extends AbstractPostgresIntegrationTest {

    static final String PW = "Sup3r-Admin-pass!";
    static final String NEW_PW = "Br4nd-New-Pass!";
    static final String PATH = "/api/v1/platform/auth/change-password";
    static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired MockMvc mvc;
    @Autowired PlatformAdminRepository admins;
    @Autowired PasswordEncoder encoder;
    @Autowired JWTUtil jwtUtil;
    @Autowired PlatformJwtService platformJwt;
    @Autowired TestTenants tenants;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private static String nextIp() {
        int n = SEQ.incrementAndGet();
        return "10.55." + (n / 250) + "." + (n % 250 + 1);
    }

    private PlatformAdmin newAdmin(boolean active) {
        String email = "chg" + SEQ.incrementAndGet() + "@platform.test";
        return admins.save(PlatformAdmin.builder().email(email).password(encoder.encode(PW)).fullName("Root")
                .isActive(active).build());
    }

    private MockHttpServletResponse login(String email, String pw) throws Exception {
        return mvc.perform(post("/api/v1/platform/auth/login").with(r -> {
            r.setRemoteAddr(nextIp());
            return r;
        }).contentType("application/json").content(
                "{\"email\":\"" + email + "\",\"password\":\"" + pw + "\"}")).andReturn().getResponse();
    }

    private record Session(Cookie access, Cookie refresh) {
        Cookie[] both() {
            return new Cookie[]{access, refresh};
        }
    }

    private Session session(PlatformAdmin a, String pw) throws Exception {
        MockHttpServletResponse r = login(a.getEmail(), pw);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        return new Session(new Cookie("platformAccessToken", r.getCookie("platformAccessToken").getValue()),
                new Cookie("platformRefreshToken", r.getCookie("platformRefreshToken").getValue()));
    }

    private MockHttpServletResponse change(String current, String next, String ip, Cookie... cookies) throws Exception {
        var b = post(PATH).with(r -> {
            r.setRemoteAddr(ip);
            return r;
        }).contentType("application/json").content(
                "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}");
        if (cookies.length > 0) {
            b.cookie(cookies);
        }
        return mvc.perform(b).andReturn().getResponse();
    }

    private int meStatus(Cookie... cookies) throws Exception {
        var b = get("/api/v1/platform/auth/me");
        if (cookies.length > 0) {
            b.cookie(cookies);
        }
        return mvc.perform(b).andReturn().getResponse().getStatus();
    }

    private int refreshStatus(Cookie refresh) throws Exception {
        return mvc.perform(post("/api/v1/platform/auth/refresh").with(r -> {
            r.setRemoteAddr(nextIp());
            return r;
        }).cookie(refresh)).andReturn().getResponse().getStatus();
    }

    private int version(PlatformAdmin a) {
        return admins.findById(a.getId()).orElseThrow().getTokenVersion();
    }

    // ---- current password, policy, reuse ---------------------------------------------------------------------

    @Test
    void wrongCurrentPassword_is400_withCode_changesNothing_noCookies() throws Exception {
        PlatformAdmin a = newAdmin(true);
        Session s = session(a, PW);
        String before = admins.findById(a.getId()).orElseThrow().getPassword();
        MockHttpServletResponse r = change("Wrong-Passw0rd!x", NEW_PW, nextIp(), s.both());
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(r.getContentAsString()).contains("CURRENT_PASSWORD_INCORRECT");
        assertThat(r.getHeaders("Set-Cookie")).isEmpty();
        assertThat(admins.findById(a.getId()).orElseThrow().getPassword()).isEqualTo(before);
        assertThat(version(a)).isZero();
        assertThat(meStatus(s.access)).isEqualTo(200);
        // wrong current + weak new: still only the current-password error (no policy hint for a non-owner)
        assertThat(change("Wrong-Passw0rd!x", "weak", nextIp(), s.both()).getContentAsString())
                .contains("CURRENT_PASSWORD_INCORRECT");
    }

    @Test
    void passwordPolicyAndReuse_areEnforced() throws Exception {
        PlatformAdmin a = newAdmin(true);
        Session s = session(a, PW);
        for (String weak : List.of("Ab1!", "n3w-passw0rd!", "New-Password!", "NewPassw0rdX")) {
            assertThat(change(PW, weak, nextIp(), s.both()).getStatus()).as(weak).isEqualTo(400);
        }
        // 73+ bytes is a 400 from request validation before anything is hashed
        assertThat(change(PW, "Aa1!" + "x".repeat(80), nextIp(), s.both()).getStatus()).isEqualTo(400);
        MockHttpServletResponse same = change(PW, PW, nextIp(), s.both());
        assertThat(same.getStatus()).isEqualTo(400);
        assertThat(same.getContentAsString()).contains("New password cannot be same as current password");
        assertThat(version(a)).as("rejected requests revoke nothing").isZero();
    }

    // ---- revocation ------------------------------------------------------------------------------------------

    @Test
    void success_revokesEveryOlderToken_reissuesTheCallersSession_andLeavesOtherAdminsAlone() throws Exception {
        PlatformAdmin a = newAdmin(true);
        PlatformAdmin other = newAdmin(true);
        Session phone = session(a, PW);
        Session laptop = session(a, PW);
        Session othersSession = session(other, PW);
        // a token minted before the tv claim existed counts as version 0: valid until the first change
        Cookie legacyAccess = new Cookie("platformAccessToken", platformJwt.issueAccess(a.getId(), a.getEmail(), "Root"));
        assertThat(meStatus(legacyAccess)).isEqualTo(200);

        MockHttpServletResponse r = change(PW, NEW_PW, nextIp(), phone.both());
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        assertThat(r.getContentAsString()).doesNotContain(PW).doesNotContain(NEW_PW);
        assertThat(version(a)).isEqualTo(1);

        assertThat(meStatus(phone.access)).as("old access, same device").isEqualTo(401);
        assertThat(meStatus(laptop.access)).as("old access, other device").isEqualTo(401);
        assertThat(meStatus(legacyAccess)).as("legacy untagged token").isEqualTo(401);
        assertThat(refreshStatus(phone.refresh)).as("old refresh, same device").isEqualTo(401);
        assertThat(refreshStatus(laptop.refresh)).as("old refresh, other device").isEqualTo(401);
        // a protected platform route refuses the old token as well
        assertThat(mvc.perform(get("/api/v1/platform/tenants").cookie(laptop.access)).andReturn().getResponse().getStatus())
                .isEqualTo(401);

        Cookie newAccess = new Cookie("platformAccessToken", r.getCookie("platformAccessToken").getValue());
        Cookie newRefresh = new Cookie("platformRefreshToken", r.getCookie("platformRefreshToken").getValue());
        assertThat(meStatus(newAccess)).isEqualTo(200);
        assertThat(refreshStatus(newRefresh)).isEqualTo(200);
        assertThat(login(a.getEmail(), PW).getStatus()).as("old password").isEqualTo(401);
        MockHttpServletResponse fresh = login(a.getEmail(), NEW_PW);
        assertThat(fresh.getStatus()).isEqualTo(200);

        // another admin: untouched row, untouched session
        assertThat(version(other)).isZero();
        assertThat(meStatus(othersSession.access)).isEqualTo(200);
        assertThat(refreshStatus(othersSession.refresh)).isEqualTo(200);
    }

    @Test
    void aRefreshedAccessTokenCarriesTheCurrentVersion() throws Exception {
        PlatformAdmin a = newAdmin(true);
        Session s = session(a, PW);
        MockHttpServletResponse ch = change(PW, NEW_PW, nextIp(), s.both());
        MockHttpServletResponse rr = mvc.perform(post("/api/v1/platform/auth/refresh").with(x -> {
            x.setRemoteAddr(nextIp());
            return x;
        }).cookie(new Cookie("platformRefreshToken", ch.getCookie("platformRefreshToken").getValue()))).andReturn().getResponse();
        assertThat(rr.getStatus()).isEqualTo(200);
        assertThat(meStatus(new Cookie("platformAccessToken", rr.getCookie("platformAccessToken").getValue()))).isEqualTo(200);
    }

    @Test
    void deactivatedAdminCannotChangeAPassword() throws Exception {
        PlatformAdmin a = newAdmin(true);
        Session s = session(a, PW);
        PlatformAdmin row = admins.findById(a.getId()).orElseThrow();
        row.setActive(false);
        admins.save(row);
        assertThat(change(PW, NEW_PW, nextIp(), s.both()).getStatus()).isEqualTo(401);
        assertThat(encoder.matches(PW, admins.findById(a.getId()).orElseThrow().getPassword())).isTrue();
    }

    // ---- rate limit ------------------------------------------------------------------------------------------

    @Test
    void perAdminThrottle_everyAttemptCounts_andTheRightPasswordIsRefusedWhileLimited() throws Exception {
        PlatformAdmin a = newAdmin(true);
        Session s = session(a, PW);
        for (int i = 0; i < 12; i++) {
            assertThat(change("Guess-" + i + "-Passw0rd!", NEW_PW, nextIp(), s.both()).getStatus()).isEqualTo(400);
        }
        MockHttpServletResponse limited = change("Guess-4-Passw0rd!", NEW_PW, nextIp(), s.both());
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isNotNull();
        assertThat(change(PW, NEW_PW, nextIp(), s.both()).getStatus()).isEqualTo(429);
        assertThat(encoder.matches(PW, admins.findById(a.getId()).orElseThrow().getPassword())).isTrue();
    }

    @Test
    void perIpThrottle_appliesAcrossAdmins() throws Exception {
        String ip = nextIp();
        int limited = 0;
        for (int i = 0; i < 8; i++) {
            PlatformAdmin a = newAdmin(true);
            Session s = session(a, PW);
            int st = change("Wrong-Passw0rd!x", NEW_PW, ip, s.both()).getStatus();
            if (st == 429) {
                limited++;
            } else {
                assertThat(st).isEqualTo(400);
            }
        }
        assertThat(limited).isEqualTo(2); // capacity 6 per hour in this class
    }

    // ---- role boundaries -------------------------------------------------------------------------------------

    @Test
    void anonymousAndEveryTenantRoleAreRefused_nothingChanges() throws Exception {
        PlatformAdmin a = newAdmin(true);
        assertThat(change(PW, NEW_PW, nextIp()).getStatus()).isEqualTo(401);
        TestTenant acme = tenants.ensureTenant("acme");
        for (String role : List.of("ADMIN", "CUSTOMER", "FRONT_DESK")) {
            String token = tenants.runAs(acme, () -> jwtUtil.generateAccessToken(acme.adminEmail(), role, 1L, "X"));
            // the tenant token under every cookie name that could ever be read on the platform plane
            for (String name : List.of("accessToken", "platformAccessToken")) {
                assertThat(change(PW, NEW_PW, nextIp(), new Cookie(name, token)).getStatus()).as(role + " in " + name).isEqualTo(401);
            }
        }
        // a platform REFRESH token is not an access token
        Session s = session(a, PW);
        assertThat(change(PW, NEW_PW, nextIp(), new Cookie("platformAccessToken", s.refresh.getValue())).getStatus()).isEqualTo(401);
        assertThat(encoder.matches(PW, admins.findById(a.getId()).orElseThrow().getPassword())).isTrue();
        assertThat(version(a)).isZero();
    }

    @Test
    void theAdminDoesNotNeedToTrustAnyBodyIdentity_onlyThePrincipalIsChanged() throws Exception {
        PlatformAdmin a = newAdmin(true);
        PlatformAdmin victim = newAdmin(true);
        Session s = session(a, PW);
        var b = post(PATH).with(r -> {
            r.setRemoteAddr(nextIp());
            return r;
        }).cookie(s.both()).contentType("application/json").content("{\"currentPassword\":\"" + PW + "\",\"newPassword\":\""
                + NEW_PW + "\",\"email\":\"" + victim.getEmail() + "\",\"id\":" + victim.getId() + "}");
        assertThat(mvc.perform(b).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(version(a)).isEqualTo(1);
        assertThat(version(victim)).isZero();
        assertThat(encoder.matches(PW, admins.findById(victim.getId()).orElseThrow().getPassword())).isTrue();
    }

    // ---- concurrency -----------------------------------------------------------------------------------------

    @Test
    void twoConcurrentChanges_serialise_exactlyOneWins_versionBumpedOnce() throws Exception {
        PlatformAdmin a = newAdmin(true);
        Session s = session(a, PW);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> fs = new java.util.ArrayList<>();
            for (String n : List.of("First-Passw0rd!", "Second-Passw0rd!")) {
                fs.add(pool.submit(() -> {
                    go.await();
                    return change(PW, n, nextIp(), s.both()).getStatus();
                }));
            }
            go.countDown();
            List<Integer> st = new java.util.ArrayList<>();
            for (Future<Integer> f : fs) {
                st.add(f.get(60, TimeUnit.SECONDS));
            }
            assertThat(st).containsExactlyInAnyOrder(200, 400);
        } finally {
            pool.shutdownNow();
        }
        assertThat(version(a)).isEqualTo(1);
    }
}
