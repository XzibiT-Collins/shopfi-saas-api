package com.example.perfume_budget.tenantauth;

import com.example.perfume_budget.support.TestPlans;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * PLAN-02 AC7 / plans.md 15 and 18 item 9: LOGIN_OTP is a request-content gate (only off -> on of an audience is checked,
 * also in read-only mode); turning it off or re-sending the stored values is never a 402; and the login keeps reading the
 * flag only, so a downgrade (or an expired / canceled subscription) never removes OTP from an account that has it on.
 */
class LoginOtpEntitlementIntegrationTest extends AbstractTenantWebTest {

    private static final String FLAG = "/api/v1/admin/feature-flags/LOGIN_OTP";

    @AfterEach
    void restore() {
        setLoginOtpFlag(acme, false);
        tenants.acme(); // back to the test tenants' plan
    }

    private void plan(long planId, String status) {
        jdbc.update("UPDATE master.tenants SET plan_id = ?, subscription_status = ?, trial_ends_at = NULL WHERE slug = 'acme'",
                planId, status);
    }

    private Cookie admin() throws Exception {
        return cookie("accessToken", setCookieValue(loginPassword("acme", acme.adminEmail(), acme.adminPassword()), "accessToken"));
    }

    private MvcResult setFlag(Cookie who, boolean admin, boolean desk, boolean customer) throws Exception {
        return mvc.perform(patch(FLAG).header("X-Tenant-ID", "acme").cookie(who).contentType("application/json")
                .content(json.writeValueAsString(Map.of("adminEnabled", admin, "frontDeskEnabled", desk, "customerEnabled", customer))))
                .andReturn();
    }

    private static JsonNode body(MvcResult r) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(r.getResponse().getContentAsString());
    }

    @Test
    void onlyOffToOnIsGated_offAndUnchangedAreNever402() throws Exception {
        plan(TestPlans.STARTER_ID, "TRIALING");
        Cookie a = admin();
        assertThat(setFlag(a, false, false, false).getResponse().getStatus()).as("unchanged off").isEqualTo(200);
        MvcResult on = setFlag(a, true, false, false);
        assertThat(on.getResponse().getStatus()).isEqualTo(402);
        assertThat(body(on).at("/data/reason").asText()).isEqualTo("NOT_IN_PLAN");
        assertThat(body(on).at("/data/feature").asText()).isEqualTo("LOGIN_OTP");
        assertThat(body(on).at("/data/requiredPlans").toString()).isEqualTo("[\"GROWTH\",\"SCALE\"]");

        // a tenant that had it on (earlier plan) keeps it, may re-send it unchanged and turn it off, but not on again
        setLoginOtpFlag(acme, true);
        assertThat(setFlag(a, true, true, true).getResponse().getStatus()).as("unchanged on").isEqualTo(200);
        assertThat(setFlag(a, false, true, true).getResponse().getStatus()).as("admin off").isEqualTo(200);
        assertThat(setFlag(a, true, true, true).getResponse().getStatus()).as("admin back on").isEqualTo(402);
        assertThat(setFlag(a, false, false, false).getResponse().getStatus()).as("all off").isEqualTo(200);
    }

    @Test
    void readOnly_offToOnIs402Inactive_offStillWorks() throws Exception {
        plan(TestPlans.GROWTH_ID, "CANCELED");
        Cookie a = admin();
        MvcResult on = setFlag(a, false, true, false);
        assertThat(on.getResponse().getStatus()).isEqualTo(402);
        assertThat(body(on).at("/data/reason").asText()).isEqualTo("SUBSCRIPTION_INACTIVE");
        setLoginOtpFlag(acme, true);
        assertThat(setFlag(a, false, false, false).getResponse().getStatus()).isEqualTo(200);
        plan(TestPlans.GROWTH_ID, "ACTIVE");
        assertThat(setFlag(a, true, false, false).getResponse().getStatus()).as("entitled and active").isEqualTo(200);
    }

    /** Security M2 / 18 item 9: downgrade with the flag on -> the login still asks for the one-time code. */
    @Test
    void aDowngradeOrACanceledSubscriptionNeverRemovesOtpFromTheLogin() throws Exception {
        plan(TestPlans.GROWTH_ID, "ACTIVE");
        Cookie a = admin();
        assertThat(setFlag(a, true, false, false).getResponse().getStatus()).isEqualTo(200);
        for (String status : new String[]{"TRIALING", "CANCELED"}) {
            plan(TestPlans.STARTER_ID, status);
            MvcResult login = loginPassword("acme", acme.adminEmail(), acme.adminPassword());
            assertThat(login.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(login).at("/data/requiresOtp").asBoolean()).as("OTP still required on " + status).isTrue();
            assertThat(setCookieValue(login, "accessToken")).as("no session before the code").isNull();
        }
    }
}
