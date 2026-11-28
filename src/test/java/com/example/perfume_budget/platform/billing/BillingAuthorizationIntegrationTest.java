package com.example.perfume_budget.platform.billing;

import com.example.perfume_budget.support.TestTenant;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * BILL-01 AC8 / S2: authorization of EVERY platform billing route on the real filter chain: anonymous 401, a tenant token of
 * every role (cookie or bearer, even with the platform admin's own e-mail) 401, a non-SUPER_ADMIN platform principal 403, only
 * the SUPER_ADMIN passes; and nothing an unauthorised caller sent changed anything.
 */
class BillingAuthorizationIntegrationTest extends AbstractBillingIntegrationTest {

    private List<MockHttpServletRequestBuilder> routes() {
        String json = "application/json";
        return List.of(get(SETTINGS), put(SETTINGS).contentType(json).content("{}"),
                post(SETTINGS + "/number-preview").contentType(json).content("{\"invoiceNumberFormat\":\"INV-{YYYY}-{NNNNNN}\"}"),
                get(SETTINGS + "/audit"), get(CREDS),
                put(CREDS + "/test").contentType(json).content(credBody(TEST_PK, TEST_SK)),
                post(CREDS + "/test/verify"));
    }

    @Test
    void everyRoute_anonymous401_tenantTokensOfEveryRole401_nonSuperAdmin403_superAdminPasses() throws Exception {
        TestTenant a = tenants.ensureTenant("billing-a");
        long audit = settingsAuditRows();
        for (MockHttpServletRequestBuilder b : routes()) {
            assertThat(call(b).getStatus()).as("anonymous " + b).isEqualTo(401);
        }
        for (String role : List.of("ADMIN", "CUSTOMER", "FRONT_DESK")) {
            String token = tenants.runAs(a, () -> jwtUtil.generateAccessToken(a.adminEmail(), role, 1L, "Someone"));
            String sameEmail = tenants.runAs(a, () -> jwtUtil.generateAccessToken(admin.getEmail(), role, 1L, "Someone"));
            for (Cookie c : List.of(new Cookie("accessToken", token), new Cookie("platformAccessToken", token),
                    new Cookie("platformAccessToken", sameEmail))) {
                for (MockHttpServletRequestBuilder b : routes()) {
                    int s = call(b.cookie(c).header("Authorization", "Bearer " + token)).getStatus();
                    assertThat(s).as(role + " " + c.getName() + " " + b).isEqualTo(401);
                }
            }
        }
        for (MockHttpServletRequestBuilder b : routes()) {
            assertThat(call(b.with(user("someone").roles("ADMIN"))).getStatus()).as("non-SUPER_ADMIN " + b).isEqualTo(403);
        }
        // nothing an unauthorised caller sent changed anything (the SUPER_ADMIN runs below are the only writers)
        assertThat(settingsAuditRows()).isEqualTo(audit);
        assertThat(scalar("SELECT version FROM master.billing_settings")).isZero();
        assertThat(scalar("SELECT count(*) FROM master.billing_paystack_credentials WHERE secret_key_encrypted IS NOT NULL")).isZero();
        assertThat(fake.requests()).as("no Paystack call without a SUPER_ADMIN").isEmpty();

        for (MockHttpServletRequestBuilder b : routes()) {
            int s = call(b.cookie(adminCookie).header("Origin", CONSOLE)).getStatus();
            assertThat(s).as("SUPER_ADMIN " + b).isNotIn(401, 403);
        }
    }

    @Test
    void writesAcceptJsonOnly_stayBehindThePlatformOriginGuard_andThereAreNoOtherVerbs() throws Exception {
        String body = settingsBody().toString();
        for (String type : List.of("text/plain", "application/x-www-form-urlencoded")) {
            assertThat(call(put(SETTINGS).cookie(adminCookie).header("Origin", CONSOLE).contentType(type).content(body)).getStatus())
                    .as(type).isEqualTo(415);
            assertThat(call(post(SETTINGS + "/number-preview").cookie(adminCookie).header("Origin", CONSOLE).contentType(type).content(body)).getStatus())
                    .as(type).isEqualTo(415);
            assertThat(call(put(CREDS + "/test").cookie(adminCookie).header("Origin", CONSOLE).contentType(type).content(credBody(TEST_PK, TEST_SK))).getStatus())
                    .as(type).isEqualTo(415);
        }
        for (String origin : List.of("https://evil.example", "https://acme.shopfi.test")) {
            assertThat(call(put(SETTINGS).cookie(adminCookie).header("Origin", origin).contentType("application/json").content(body)).getStatus())
                    .as(origin).isEqualTo(403);
            assertThat(call(put(CREDS + "/test").cookie(adminCookie).header("Origin", origin).contentType("application/json")
                    .content(credBody(TEST_PK, TEST_SK))).getStatus()).as(origin).isEqualTo(403);
        }
        assertThat(scalar("SELECT version FROM master.billing_settings")).isZero();
        assertThat(scalar("SELECT count(*) FROM master.billing_paystack_credentials WHERE secret_key_encrypted IS NOT NULL")).isZero();
        for (MockHttpServletRequestBuilder b : List.of(delete(SETTINGS), patch(SETTINGS).contentType("application/json").content("{}"),
                delete(CREDS + "/test"), post(SETTINGS), delete(CREDS))) {
            int s = asAdmin(b, null).getStatus();
            assertThat(s).as(String.valueOf(b)).isIn(405, 415);
        }
    }

    @Test
    void devRoutesAreDeniedWithoutTheDevFlag_evenForTheSuperAdmin() throws Exception {
        for (MockHttpServletRequestBuilder b : List.of(get("/api/v1/dev/billing/clock"),
                post("/api/v1/dev/billing/clock/advance").contentType("application/json").content("{\"seconds\":60}"), post("/api/v1/dev/billing/run"))) {
            MockHttpServletResponse anon = call(b);
            assertThat(anon.getStatus()).as("anonymous " + b).isIn(400, 401, 403); // never reaches a handler (400: no tenant on this host)
        }
        for (MockHttpServletRequestBuilder b : List.of(get("/api/v1/dev/billing/clock"), post("/api/v1/dev/billing/run"))) {
            assertThat(call(b.cookie(adminCookie).header("Origin", CONSOLE)).getStatus()).as("SUPER_ADMIN " + b).isIn(401, 403);
        }
        assertThat(billingClock.offsetSeconds()).isZero();
    }
}
