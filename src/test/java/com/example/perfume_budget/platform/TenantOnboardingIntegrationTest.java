package com.example.perfume_budget.platform;

import com.example.perfume_budget.events.UserRegistrationEvent;
import com.example.perfume_budget.notification.EmailService;
import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import com.example.perfume_budget.support.PostgresTestDatabase;
import com.example.perfume_budget.tenancy.ProvisioningRequest;
import com.example.perfume_budget.tenancy.ProvisioningResult;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantProvisioningService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** PROV-02 end to end against real PostgreSQL: no manual DB work between signup and ACTIVE. */
@AutoConfigureMockMvc
// Closes this class's (uniquely configured) context afterwards so its Hikari pool and the cached contexts of the
// rest of the suite stay within the shared PostgreSQL connection-slot limit.
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Import(TenantOnboardingIntegrationTest.OtpCapture.class)
@TestPropertySource(properties = {
        "tenant.base-domain=shopfi.test",
        "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=0",
        "cors.allowed-origins=https://onb-happy.shopfi.test",
        "onboarding.max-otp-attempts=3",
        "onboarding.signup-ip.capacity=6",
        "onboarding.signup-email.capacity=2",
        "onboarding.verify-ip.capacity=1000",
        "onboarding.verify-email.capacity=1000",
        "onboarding.status-ip.capacity=100000"
})
class TenantOnboardingIntegrationTest extends AbstractPostgresIntegrationTest {

    static final String PASSWORD = "Onb0ard!ng-pass";

    /** Captures the OTP at the point the existing email path would receive it (synchronously, test only). */
    @TestConfiguration
    static class OtpCapture {
        static final Map<String, String> OTPS = new ConcurrentHashMap<>();

        @Bean
        OtpListener otpListener() {
            return new OtpListener();
        }

        static class OtpListener {
            @EventListener
            void on(UserRegistrationEvent e) {
                OTPS.put(e.email(), e.otp());
            }
        }
    }

    @MockitoBean EmailService emailService;
    @MockitoSpyBean TenantProvisioningService provisioning;
    @Autowired MockMvc mvc;

    private static RequestPostProcessor ip(String addr) {
        return r -> {
            r.setRemoteAddr(addr);
            return r;
        };
    }

    private MvcResult signup(String slug, String email, String password, String ip) throws Exception {
        return mvc.perform(post("/api/v1/platform/onboarding/signup").with(ip(ip))
                .contentType("application/json")
                .content("{\"slug\":\"" + slug + "\",\"displayName\":\"Shop " + slug + "\",\"ownerEmail\":\"" + email
                        + "\",\"ownerFullName\":\"Owner\",\"password\":\"" + password + "\",\"plan\":\"FREE\"}"))
                .andReturn();
    }

    private MvcResult verify(String slug, String email, String otp, String ip) throws Exception {
        return mvc.perform(post("/api/v1/platform/onboarding/verify").with(ip(ip))
                .contentType("application/json")
                .content("{\"slug\":\"" + slug + "\",\"email\":\"" + email + "\",\"otp\":\"" + otp + "\"}")).andReturn();
    }

    private MvcResult status(String slug, String ip) throws Exception {
        return mvc.perform(get("/api/v1/platform/onboarding/status").param("slug", slug).with(ip(ip))).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    private static <T> T jdbc(SqlFn<T> fn) {
        try (Connection c = PostgresTestDatabase.connect()) {
            return fn.apply(c);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    interface SqlFn<T> { T apply(Connection c) throws SQLException; }

    private static long count(String sql) {
        return jdbc(c -> {
            try (var st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    private String waitForStatus(String slug, String ip, String wanted) throws Exception {
        long deadline = System.currentTimeMillis() + 90_000;
        String last = "";
        while (System.currentTimeMillis() < deadline) {
            MvcResult r = status(slug, ip);
            last = body(r);
            if (r.getResponse().getStatus() == 200 && last.contains("\"status\":\"" + wanted + "\"")) {
                return last;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("status never reached " + wanted + "; last=" + last);
    }

    // ---- AC6: the happy path ------------------------------------------------------------------------------

    @Test
    void signup_verify_poll_active_withNoManualDbWork_andAdminCanLogIn() throws Exception {
        AtomicReference<Object> workerContext = new AtomicReference<>("unset");
        org.mockito.Mockito.doAnswer(inv -> {
            workerContext.set(TenantContext.get());
            return inv.callRealMethod();
        }).when(provisioning).provision(org.mockito.ArgumentMatchers.any(ProvisioningRequest.class));

        String ip = "10.0.0.1";
        MvcResult s = signup("onb-happy", "Happy@Onb.test", "  " + PASSWORD + "  ", ip);
        assertThat(s.getResponse().getStatus()).as(body(s)).isEqualTo(201);
        assertThat(body(s)).contains("PENDING").doesNotContain(PASSWORD).doesNotContain("happy@onb.test");
        assertThat(status("onb-happy", ip).getResponse().getContentAsString()).contains("\"status\":\"PENDING\"");

        // master holds only hashes, never the password or the OTP
        String otp = OtpCapture.OTPS.get("happy@onb.test");
        assertThat(otp).matches("\\d{6}");
        String storedHash = jdbc(c -> {
            try (var ps = c.prepareStatement("SELECT s.password_hash || '|' || s.otp_hash FROM master.tenant_signups s "
                    + "JOIN master.tenants t ON t.id = s.tenant_id WHERE t.slug = 'onb-happy'");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        });
        assertThat(storedHash).startsWith("$2").doesNotContain(PASSWORD).doesNotContain("|" + otp);
        assertThat(count("SELECT count(*) FROM master.tenant_signups s JOIN master.tenants t ON t.id=s.tenant_id "
                + "WHERE t.slug='onb-happy' AND s.otp_hash = '" + otp + "'")).isZero();

        MvcResult v = verify("onb-happy", "happy@onb.test", otp, ip);
        assertThat(v.getResponse().getStatus()).as(body(v)).isEqualTo(202);
        assertThat(body(v)).contains("PROVISIONING");

        String done = waitForStatus("onb-happy", ip, "ACTIVE");
        assertThat(done).contains("\"storefrontUrl\":\"https://onb-happy.shopfi.test\"")
                .contains("\"adminLoginUrl\":\"https://onb-happy.shopfi.test/admin/login\"")
                .doesNotContain("happy@onb.test").doesNotContain("tenant_onb_happy");

        // async worker started with a clean context (dedicated executor, no PLATFORM context inherited)
        assertThat(workerContext.get()).isNull();
        // password hash is dropped once provisioning succeeded
        assertThat(count("SELECT count(*) FROM master.tenant_signups s JOIN master.tenants t ON t.id=s.tenant_id "
                + "WHERE t.slug='onb-happy'")).isZero();
        assertThat(count("SELECT count(*) FROM tenant_onb_happy.users WHERE email='happy@onb.test' AND roles='ADMIN'")).isEqualTo(1);

        // the stripped password is the admin credential
        MvcResult login = mvc.perform(post("/api/v1/auth/login").header("Origin", "https://onb-happy.shopfi.test")
                .contentType("application/json")
                .content("{\"email\":\"happy@onb.test\",\"password\":\"" + PASSWORD + "\"}")).andReturn();
        assertThat(login.getResponse().getStatus()).as(body(login)).isEqualTo(200);
        assertThat(login.getResponse().getCookie("accessToken")).isNotNull().isInstanceOf(Cookie.class);
        org.mockito.Mockito.reset(provisioning);
    }

    // ---- negatives ----------------------------------------------------------------------------------------

    @Test
    void duplicateSlug_is409_andSendsNoSecondOtp() throws Exception {
        assertThat(signup("onb-dup", "dup1@onb.test", PASSWORD, "10.0.0.2").getResponse().getStatus()).isEqualTo(201);
        String firstOtp = OtpCapture.OTPS.get("dup1@onb.test");
        MvcResult dup = signup("ONB-DUP", "dup2@onb.test", PASSWORD, "10.0.0.2");
        assertThat(dup.getResponse().getStatus()).isEqualTo(409);
        assertThat(OtpCapture.OTPS).doesNotContainKey("dup2@onb.test");
        assertThat(OtpCapture.OTPS.get("dup1@onb.test")).isEqualTo(firstOtp);
        // same owner email with another slug is also refused
        assertThat(signup("onb-dup-b", "dup1@onb.test", PASSWORD, "10.0.0.2").getResponse().getStatus()).isEqualTo(409);
        assertThat(count("SELECT count(*) FROM master.tenants WHERE slug IN ('onb-dup-b')")).isZero();
    }

    @Test
    void reservedAndInvalidSlugsAndWeakPassword_are400_nothingCreated() throws Exception {
        int n = 0;
        for (String slug : new String[]{"admin", "tenant_template", "pg-catalog", "x;drop", "ab"}) {
            MvcResult r = signup(slug, "res" + (n++) + "@onb.test", PASSWORD, "10.0.0.3");
            assertThat(r.getResponse().getStatus()).as(slug).isEqualTo(400);
        }
        assertThat(signup("onb-weak", "weak@onb.test", "short", "10.0.0.3").getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(post("/api/v1/platform/onboarding/signup").with(ip("10.0.0.3"))
                .contentType("application/json").content("{\"slug\":\"onb-nomail\"}")).andReturn()
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(count("SELECT count(*) FROM master.tenants WHERE slug LIKE 'onb-weak' OR slug LIKE 'onb-nomail'")).isZero();
        assertThat(count("SELECT count(*) FROM master.tenants WHERE slug IN ('admin','pg-catalog')")).isZero();
    }

    @Test
    void wrongOtp_is400_thenLockout429_evenWithCorrectOtp_andTenantStaysPending() throws Exception {
        String ip = "10.0.0.4";
        assertThat(signup("onb-lock", "lock@onb.test", PASSWORD, ip).getResponse().getStatus()).isEqualTo(201);
        String otp = OtpCapture.OTPS.get("lock@onb.test");
        String wrong = otp.equals("000000") ? "000001" : "000000";

        for (int i = 0; i < 3; i++) {
            MvcResult r = verify("onb-lock", "lock@onb.test", wrong, ip);
            assertThat(r.getResponse().getStatus()).as("attempt " + i).isEqualTo(400);
            assertThat(body(r)).doesNotContain(otp);
        }
        MvcResult locked = verify("onb-lock", "lock@onb.test", otp, ip);
        assertThat(locked.getResponse().getStatus()).isEqualTo(429);
        assertThat(locked.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat(status("onb-lock", ip).getResponse().getContentAsString()).contains("\"status\":\"PENDING\"");
        assertThat(count("SELECT count(*) FROM information_schema.schemata WHERE schema_name='tenant_onb_lock'")).isZero();
    }

    @Test
    void verify_unknownSlugOrWrongEmail_is400() throws Exception {
        assertThat(signup("onb-who", "who@onb.test", PASSWORD, "10.0.0.5").getResponse().getStatus()).isEqualTo(201);
        String otp = OtpCapture.OTPS.get("who@onb.test");
        assertThat(verify("onb-ghost", "who@onb.test", otp, "10.0.0.5").getResponse().getStatus()).isEqualTo(400);
        assertThat(verify("onb-who", "evil@onb.test", otp, "10.0.0.5").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void expiredOtp_is400() throws Exception {
        assertThat(signup("onb-exp", "exp@onb.test", PASSWORD, "10.0.0.6").getResponse().getStatus()).isEqualTo(201);
        String otp = OtpCapture.OTPS.get("exp@onb.test");
        jdbc(c -> {
            try (var st = c.createStatement()) {
                return st.executeUpdate("UPDATE master.tenant_signups SET otp_expires_at = now() - interval '1 minute' "
                        + "WHERE tenant_id = (SELECT id FROM master.tenants WHERE slug='onb-exp')");
            }
        });
        assertThat(verify("onb-exp", "exp@onb.test", otp, "10.0.0.6").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void perEmailRateLimit_secondSignupAllowedThirdIs429() throws Exception {
        // capacity 2 per email: attempts 1 and 2 reach the service (duplicate or not), the 3rd is limited
        assertThat(signup("onb-rl-a", "rl@onb.test", PASSWORD, "10.0.1.1").getResponse().getStatus()).isEqualTo(201);
        assertThat(signup("onb-rl-b", "rl@onb.test", PASSWORD, "10.0.1.2").getResponse().getStatus()).isEqualTo(409);
        MvcResult limited = signup("onb-rl-c", "rl@onb.test", PASSWORD, "10.0.1.3");
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(limited.getResponse().getHeader("Retry-After")).isNotNull();
    }

    @Test
    void perIpRateLimit_seventhSignupFromOneIpIs429_otherIpUnaffected() throws Exception {
        for (int i = 0; i < 6; i++) {
            assertThat(signup("onb-ip-" + i, "ip" + i + "@onb.test", PASSWORD, "10.0.2.1").getResponse().getStatus()).isEqualTo(201);
        }
        assertThat(signup("onb-ip-6", "ip6@onb.test", PASSWORD, "10.0.2.1").getResponse().getStatus()).isEqualTo(429);
        assertThat(signup("onb-ip-6", "ip6@onb.test", PASSWORD, "10.0.2.2").getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void status_unknownOrInvalidSlug_is404_missingParam400() throws Exception {
        assertThat(status("onb-never-existed", "10.0.0.7").getResponse().getStatus()).isEqualTo(404);
        assertThat(status("bad slug;", "10.0.0.7").getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/api/v1/platform/onboarding/status").with(ip("10.0.0.7"))).andReturn()
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void endpointsArePublic_noAuthNeeded_andNeedNoTenantHeader() throws Exception {
        // would be 401/400 ("Tenant could not be determined") if permitAll or the platform path bypass were missing
        assertThat(status("onb-whatever", "10.0.0.8").getResponse().getStatus()).isEqualTo(404);
        assertThat(verify("onb-whatever", "a@b.test", "123456", "10.0.0.8").getResponse().getStatus()).isEqualTo(400);
    }

    private MvcResult resend(String slug, String email, String ip) throws Exception {
        return mvc.perform(post("/api/v1/platform/onboarding/resend").with(ip(ip))
                .contentType("application/json")
                .content("{\"slug\":\"" + slug + "\",\"email\":\"" + email + "\"}")).andReturn();
    }

    private static void ageLastOtpSend(String slug) {
        jdbc(c -> {
            try (var st = c.createStatement()) {
                return st.executeUpdate("UPDATE master.tenant_signups SET last_otp_sent_at = now() - interval '5 minutes' "
                        + "WHERE tenant_id = (SELECT id FROM master.tenants WHERE slug='" + slug + "')");
            }
        });
    }

    private static String passwordHashOf(String slug) {
        return jdbc(c -> {
            try (var ps = c.prepareStatement("SELECT s.password_hash FROM master.tenant_signups s JOIN master.tenants t "
                    + "ON t.id = s.tenant_id WHERE t.slug = '" + slug + "'"); ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    @Test
    void provisioningFailure_reportsFailed_oldOtpIsDead_resendIssuesNewCode_thatRetriesToActive() throws Exception {
        String ip = "10.0.0.9";
        assertThat(signup("onb-fail", "fail@onb.test", PASSWORD, ip).getResponse().getStatus()).isEqualTo(201);
        String otp = OtpCapture.OTPS.get("fail@onb.test");
        org.mockito.Mockito.doThrow(new com.example.perfume_budget.tenancy.TenantProvisioningException("onb-fail", true,
                new RuntimeException("boom"))).doCallRealMethod()
                .when(provisioning).provision(org.mockito.ArgumentMatchers.any(ProvisioningRequest.class));
        // simulate the saga's own FAILED marking by failing then letting the retry run for real
        jdbc(c -> {
            try (var st = c.createStatement()) {
                return st.executeUpdate("UPDATE master.tenants SET status='FAILED' WHERE slug='onb-fail'");
            }
        });
        assertThat(verify("onb-fail", "fail@onb.test", otp, ip).getResponse().getStatus()).isEqualTo(202);
        Thread.sleep(500);
        assertThat(body(status("onb-fail", ip))).contains("FAILED");

        // AC3: the code is single use, so the same OTP no longer works...
        assertThat(verify("onb-fail", "fail@onb.test", otp, ip).getResponse().getStatus()).isEqualTo(400);
        // ...and the owner recovers with a resend instead of waiting for the 24h purge
        ageLastOtpSend("onb-fail");
        MvcResult rs = resend("onb-fail", "fail@onb.test", ip);
        assertThat(rs.getResponse().getStatus()).isEqualTo(202);
        String fresh = OtpCapture.OTPS.get("fail@onb.test");
        assertThat(fresh).matches("\\d{6}");
        assertThat(verify("onb-fail", "fail@onb.test", fresh, ip).getResponse().getStatus()).isEqualTo(202);
        waitForStatus("onb-fail", ip, "ACTIVE");
        org.mockito.Mockito.reset(provisioning);
    }

    // ---- PROV-02b AC3/AC4 ---------------------------------------------------------------------------------

    @Test
    void otpIsSingleUse_replayAfterSuccessIs400_andProvisionRunsOnce() throws Exception {
        String ip = "10.0.4.1";
        assertThat(signup("onb-once", "once@onb.test", PASSWORD, ip).getResponse().getStatus()).isEqualTo(201);
        String otp = OtpCapture.OTPS.get("once@onb.test");
        org.mockito.Mockito.clearInvocations(provisioning);
        assertThat(verify("onb-once", "once@onb.test", otp, ip).getResponse().getStatus()).isEqualTo(202);
        assertThat(verify("onb-once", "once@onb.test", otp, ip).getResponse().getStatus()).isEqualTo(400);
        waitForStatus("onb-once", ip, "ACTIVE");
        assertThat(verify("onb-once", "once@onb.test", otp, ip).getResponse().getStatus()).isEqualTo(400);
        org.mockito.Mockito.verify(provisioning, org.mockito.Mockito.times(1))
                .provision(org.mockito.ArgumentMatchers.any(ProvisioningRequest.class));
    }

    @Test
    void concurrentVerifyWithTheCorrectOtp_exactlyOneWins_oneProvisionCall_oneAdmin() throws Exception {
        assertThat(signup("onb-cv", "cv@onb.test", PASSWORD, "10.0.4.2").getResponse().getStatus()).isEqualTo(201);
        String otp = OtpCapture.OTPS.get("cv@onb.test");
        org.mockito.Mockito.clearInvocations(provisioning);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        var gate = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Integer>> fs = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            fs.add(pool.submit(() -> {
                gate.await();
                return verify("onb-cv", "cv@onb.test", otp, "10.0.4.2").getResponse().getStatus();
            }));
        }
        gate.countDown();
        int accepted = 0;
        for (var f : fs) {
            int st = f.get(60, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(st).isIn(202, 400);
            if (st == 202) accepted++;
        }
        pool.shutdown();
        assertThat(accepted).as("single-use OTP: only one concurrent verify may succeed").isEqualTo(1);
        waitForStatus("onb-cv", "10.0.4.2", "ACTIVE");
        org.mockito.Mockito.verify(provisioning, org.mockito.Mockito.times(1))
                .provision(org.mockito.ArgumentMatchers.any(ProvisioningRequest.class));
        assertThat(count("SELECT count(*) FROM tenant_onb_cv.users WHERE email='cv@onb.test'")).isEqualTo(1);
    }

    @Test
    void concurrentSignupsForTheSameSlug_exactlyOne201_restAre409_oneTenantRow() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        var gate = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Integer>> fs = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final int n = i;
            fs.add(pool.submit(() -> {
                gate.await();
                return signup("onb-race", "race" + n + "@onb.test", PASSWORD, "10.0.5." + n).getResponse().getStatus();
            }));
        }
        gate.countDown();
        int created = 0, dup = 0;
        for (var f : fs) {
            int st = f.get(60, java.util.concurrent.TimeUnit.SECONDS);
            if (st == 201) created++;
            else if (st == 409) dup++;
        }
        pool.shutdown();
        assertThat(created).isEqualTo(1);
        assertThat(dup).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM master.tenants WHERE slug='onb-race'")).isEqualTo(1);
    }

    @Test
    void failedTenant_resignupWithDifferentPassword_is409_storedHashUnchanged_attackerEmailToo() throws Exception {
        String ip = "10.0.4.3";
        assertThat(signup("onb-fbind", "fbind@onb.test", PASSWORD, ip).getResponse().getStatus()).isEqualTo(201);
        String before = passwordHashOf("onb-fbind");
        jdbc(c -> {
            try (var st = c.createStatement()) {
                return st.executeUpdate("UPDATE master.tenants SET status='FAILED' WHERE slug='onb-fbind'");
            }
        });
        assertThat(signup("onb-fbind", "fbind@onb.test", "Attacker-Pass-1", "10.0.4.4").getResponse().getStatus()).isEqualTo(409);
        assertThat(signup("onb-fbind", "attacker@onb.test", "Attacker-Pass-1", "10.0.4.5").getResponse().getStatus()).isEqualTo(409);
        assertThat(passwordHashOf("onb-fbind")).isEqualTo(before);
        assertThat(OtpCapture.OTPS).doesNotContainKey("attacker@onb.test");
    }

    @Test
    void resend_isEnumerationSafe_sameResponseForKnownUnknownAndWrongEmail_neverUnlocksOrResetsAttempts() throws Exception {
        String ip = "10.0.4.6";
        assertThat(signup("onb-rs", "rs@onb.test", PASSWORD, ip).getResponse().getStatus()).isEqualTo(201);
        String first = OtpCapture.OTPS.get("rs@onb.test");
        String wrong = first.equals("000000") ? "000001" : "000000";

        MvcResult known = resend("onb-rs", "rs@onb.test", "10.0.4.7");   // inside cooldown: silent no-op
        MvcResult unknown = resend("onb-nobody", "nobody@onb.test", "10.0.4.8");
        MvcResult other = resend("onb-rs", "intruder@onb.test", "10.0.4.9");
        assertThat(known.getResponse().getStatus()).isEqualTo(202);
        assertThat(body(unknown)).isEqualTo(body(known));
        assertThat(body(other)).isEqualTo(body(known));
        assertThat(OtpCapture.OTPS.get("rs@onb.test")).as("cooldown: no new code yet").isEqualTo(first);
        assertThat(OtpCapture.OTPS).doesNotContainKey("intruder@onb.test").doesNotContainKey("nobody@onb.test");

        // lock the signup (3 wrong codes), then resend must not unlock or reset anything
        for (int i = 0; i < 3; i++) {
            assertThat(verify("onb-rs", "rs@onb.test", wrong, ip).getResponse().getStatus()).isEqualTo(400);
        }
        ageLastOtpSend("onb-rs");
        assertThat(resend("onb-rs", "rs@onb.test", "10.0.4.7").getResponse().getStatus()).isEqualTo(202);
        assertThat(OtpCapture.OTPS.get("rs@onb.test")).as("locked: no new code").isEqualTo(first);
        assertThat(verify("onb-rs", "rs@onb.test", first, ip).getResponse().getStatus()).isEqualTo(429);
        assertThat(count("SELECT count(*) FROM master.tenant_signups s JOIN master.tenants t ON t.id=s.tenant_id "
                + "WHERE t.slug='onb-rs' AND s.failed_attempts = 3 AND s.locked_until > now() AND s.resend_count = 0")).isEqualTo(1);
    }

    @Test
    void resend_isRateLimitedPerIp() throws Exception {
        // resend-ip capacity is 10/hour by default; the 11th call from one IP is 429
        for (int i = 0; i < 10; i++) {
            assertThat(resend("onb-rl-none", "rln" + i + "@onb.test", "10.0.6.1").getResponse().getStatus()).isEqualTo(202);
        }
        MvcResult limited = resend("onb-rl-none", "rln10@onb.test", "10.0.6.1");
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(limited.getResponse().getHeader("Retry-After")).isNotNull();
    }

    // ---- PROV-02b AC1 at the controller: the limiter key is the resolved address, not a header ------------

    @Test
    void perIpLimit_cannotBeEvadedByRotatingXForwardedForRealIpOrForwarded() throws Exception {
        for (int i = 0; i < 6; i++) {
            MvcResult r = mvc.perform(post("/api/v1/platform/onboarding/signup").with(ip("10.0.7.1"))
                    .header("X-Forwarded-For", "6.6.6." + i + ", 7.7.7." + i)
                    .header("X-Real-IP", "5.5.5." + i).header("Forwarded", "for=4.4.4." + i)
                    .contentType("application/json")
                    .content("{\"slug\":\"onb-spoof" + i + "\",\"displayName\":\"S\",\"ownerEmail\":\"sp" + i
                            + "@onb.test\",\"ownerFullName\":\"O\",\"password\":\"" + PASSWORD + "\"}")).andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(201);
        }
        MvcResult seventh = mvc.perform(post("/api/v1/platform/onboarding/signup").with(ip("10.0.7.1"))
                .header("X-Forwarded-For", "9.9.9.9, 8.8.8.8").header("X-Real-IP", "1.2.3.4")
                .contentType("application/json")
                .content("{\"slug\":\"onb-spoof6\",\"displayName\":\"S\",\"ownerEmail\":\"sp6@onb.test\","
                        + "\"ownerFullName\":\"O\",\"password\":\"" + PASSWORD + "\"}")).andReturn();
        assertThat(seventh.getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void perIpLimit_ipv6AddressesInOneSlash64ShareTheBucket() throws Exception {
        for (int i = 0; i < 6; i++) {
            assertThat(signup("onb-v6-" + i, "v6" + i + "@onb.test", PASSWORD, "2001:db8:77:1:0:0:0:" + (i + 1))
                    .getResponse().getStatus()).isEqualTo(201);
        }
        assertThat(signup("onb-v6-6", "v66@onb.test", PASSWORD, "2001:db8:77:1:abcd:ef01:2345:6789")
                .getResponse().getStatus()).isEqualTo(429);
        assertThat(signup("onb-v6-6", "v66@onb.test", PASSWORD, "2001:db8:77:2::1").getResponse().getStatus()).isEqualTo(201);
    }
}
