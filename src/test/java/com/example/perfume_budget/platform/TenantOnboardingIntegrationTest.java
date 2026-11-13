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

    @Test
    void provisioningFailure_reportsFailed_andSameOtpCanRetry() throws Exception {
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
        // retry within the OTP window: second call goes to the real saga (FAILED rows are claimable)
        assertThat(verify("onb-fail", "fail@onb.test", otp, ip).getResponse().getStatus()).isEqualTo(202);
        waitForStatus("onb-fail", ip, "ACTIVE");
        org.mockito.Mockito.reset(provisioning);
    }
}
