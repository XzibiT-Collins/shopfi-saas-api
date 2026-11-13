package com.example.perfume_budget.tenantauth;

import com.example.perfume_budget.support.TestTenants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** TENANT-05 AC1: password + email-OTP login on two tenants issue tenant-bound JWTs; same email registers in both. */
class AuthLoginTenantBindingTest extends AbstractTenantWebTest {

    @AfterEach
    void flagsOff() {
        setLoginOtpFlag(acme, false);
        setLoginOtpFlag(bravo, false);
    }

    // ---- password login ----

    @Test
    void ac1_passwordLoginOnAcmeIssuesJwtBoundToAcme() throws Exception {
        MvcResult r = loginPassword("acme", acme.adminEmail(), TestTenants.ADMIN_PASSWORD);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        for (String name : new String[]{"accessToken", "refreshToken"}) {
            String token = setCookieValue(r, name);
            assertThat(token).as(name).isNotBlank();
            assertThat(jwtUtil.extractTenant(token)).as(name + " tenant claim").isEqualTo("acme");
            assertThat(jwtUtil.extractUsername(token)).isEqualTo(acme.adminEmail());
        }
    }

    @Test
    void ac1_passwordLoginOnBravoIssuesJwtBoundToBravo() throws Exception {
        MvcResult r = loginPassword("bravo", bravo.adminEmail(), TestTenants.ADMIN_PASSWORD);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(jwtUtil.extractTenant(setCookieValue(r, "accessToken"))).isEqualTo("bravo");
        assertThat(jwtUtil.extractTenant(setCookieValue(r, "refreshToken"))).isEqualTo("bravo");
    }

    @Test
    void ac1_acmeAdminCannotLogInOnBravoAndTokenIsRejectedOnBravo() throws Exception {
        // user exists only in acme's schema
        MvcResult cross = loginPassword("bravo", acme.adminEmail(), TestTenants.ADMIN_PASSWORD);
        assertThat(cross.getResponse().getStatus()).isIn(401, 403);
        assertThat(setCookieValue(cross, "accessToken")).isNull();

        // acme token replayed against bravo (shared API-host cookie) -> JWTFilter tenant mismatch
        String acmeToken = setCookieValue(loginPassword("acme", acme.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken");
        MvcResult replay = mvc.perform(get("/api/v1/auth/me").header("X-Tenant-ID", "bravo")
                .cookie(cookie("accessToken", acmeToken))).andReturn();
        assertThat(replay.getResponse().getStatus()).isEqualTo(401);
        // and it still works on its own tenant
        MvcResult ok = mvc.perform(get("/api/v1/auth/me").header("X-Tenant-ID", "acme")
                .cookie(cookie("accessToken", acmeToken))).andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
    }

    // ---- email OTP login ----

    @Test
    void ac1_emailOtpLoginOnBothTenantsIssuesTenantBoundJwts() throws Exception {
        for (var t : java.util.List.of(acme, bravo)) {
            setLoginOtpFlag(t, true);
            MvcResult step1 = loginPassword(t.slug(), t.adminEmail(), TestTenants.ADMIN_PASSWORD);
            JsonNode body = json.readTree(step1.getResponse().getContentAsString());
            assertThat(step1.getResponse().getStatus()).isEqualTo(200);
            assertThat(body.at("/data/requiresOtp").asBoolean()).isTrue();
            assertThat(setCookieValue(step1, "accessToken")).as("no JWT before OTP").isNull();
            String challenge = body.at("/data/challengeToken").asText();
            String otp = (String) redis.get("otp:login:code:" + challenge);
            assertThat(otp).isNotNull();

            MvcResult step2 = mvc.perform(postJson("/api/v1/auth/verify-login-otp", t.slug(),
                    Map.of("challengeToken", challenge, "otp", otp))).andReturn();
            assertThat(step2.getResponse().getStatus()).isEqualTo(200);
            assertThat(jwtUtil.extractTenant(setCookieValue(step2, "accessToken"))).isEqualTo(t.slug());
            assertThat(jwtUtil.extractTenant(setCookieValue(step2, "refreshToken"))).isEqualTo(t.slug());
        }
    }

    /**
     * A login challenge issued for tenant A must not be redeemable on tenant B. Redis keys
     * (otp:login:*) are not tenant-scoped, so a challenge from acme verified with X-Tenant-ID: bravo
     * can mint a bravo JWT for a same-email bravo user without that user's bravo password.
     */
    @Test
    void ac1_loginOtpChallengeIssuedForAcmeCannotBeRedeemedOnBravo() throws Exception {
        String shared = "shared-" + UUID.randomUUID() + "@example.com";
        registerAndVerify("acme", shared);
        registerAndVerify("bravo", shared);
        jdbc.update("UPDATE tenant_acme.feature_flags SET enabled = true");
        jdbc.update("UPDATE tenant_bravo.feature_flags SET enabled = true");

        MvcResult step1 = loginPassword("acme", shared, "Sup3rSecret!pw");
        JsonNode body = json.readTree(step1.getResponse().getContentAsString());
        String challenge = body.at("/data/challengeToken").asText();
        String otp = (String) redis.get("otp:login:code:" + challenge);

        MvcResult cross = mvc.perform(postJson("/api/v1/auth/verify-login-otp", "bravo",
                Map.of("challengeToken", challenge, "otp", otp))).andReturn();
        assertThat(cross.getResponse().getStatus())
                .as("acme challenge redeemed on bravo; got JWT tenant=%s", jwtOrNull(cross))
                .isGreaterThanOrEqualTo(400);
        assertThat(setCookieValue(cross, "accessToken")).isNull();
    }

    // ---- registration ----

    @Test
    void ac1_sameEmailRegistersIndependentlyInBothTenants() throws Exception {
        String email = "shopper-" + UUID.randomUUID() + "@example.com";

        MvcResult a = registerAndVerify("acme", email);
        assertThat(jwtUtil.extractTenant(setCookieValue(a, "accessToken"))).isEqualTo("acme");
        assertThat(countUsers("tenant_acme", email)).isEqualTo(1);
        assertThat(countUsers("tenant_bravo", email)).isZero();

        MvcResult b = registerAndVerify("bravo", email);
        assertThat(jwtUtil.extractTenant(setCookieValue(b, "accessToken"))).isEqualTo("bravo");
        assertThat(countUsers("tenant_acme", email)).isEqualTo(1);
        assertThat(countUsers("tenant_bravo", email)).isEqualTo(1);
        assertThat(countUsers("tenant_template", email)).isZero();

        Long idA = jdbc.queryForObject("SELECT id FROM tenant_acme.users WHERE email = ?", Long.class, email);
        String pwA = jdbc.queryForObject("SELECT password FROM tenant_acme.users WHERE email = ?", String.class, email);
        String pwB = jdbc.queryForObject("SELECT password FROM tenant_bravo.users WHERE email = ?", String.class, email);
        assertThat(idA).isNotNull();
        assertThat(pwA).isNotEqualTo(pwB); // independent rows (separate hashes)

        // duplicate within the SAME tenant is still rejected
        MvcResult dup = mvc.perform(postJson("/api/v1/auth/register", "acme", registrationBody(email))).andReturn();
        assertThat(dup.getResponse().getStatus()).isEqualTo(409);
    }

    /**
     * Pending registrations of one email in two tenants must not clobber each other. The registration
     * OTP key {@code otp:registration:<email>} is not tenant-scoped, so bravo's register overwrites acme's OTP.
     */
    @Test
    void ac1_interleavedRegistrationOtpsForSameEmailDoNotCollideAcrossTenants() throws Exception {
        String email = "interleave-" + UUID.randomUUID() + "@example.com";
        assertThat(mvc.perform(postJson("/api/v1/auth/register", "acme", registrationBody(email)))
                .andReturn().getResponse().getStatus()).isEqualTo(204);
        String otpForAcme = otpFor(email);
        assertThat(mvc.perform(postJson("/api/v1/auth/register", "bravo", registrationBody(email)))
                .andReturn().getResponse().getStatus()).isEqualTo(204);

        MvcResult verifyAcme = mvc.perform(postJson("/api/v1/auth/verify-otp", "acme",
                Map.of("email", email, "otp", otpForAcme))).andReturn();
        assertThat(verifyAcme.getResponse().getStatus())
                .as("acme's own OTP must still verify after bravo registered the same email")
                .isEqualTo(200);
    }

    // ---- helpers ----

    private String jwtOrNull(MvcResult r) {
        String t = setCookieValue(r, "accessToken");
        return t == null ? null : jwtUtil.extractTenant(t);
    }

    private Map<String, String> registrationBody(String email) {
        return Map.of("email", email, "fullName", "Test Shopper",
                "password", "Sup3rSecret!pw", "confirmPassword", "Sup3rSecret!pw");
    }

    private String otpFor(String email) {
        return (String) redis.get("otp:registration:" + email);
    }

    private MvcResult registerAndVerify(String slug, String email) throws Exception {
        assertThat(mvc.perform(postJson("/api/v1/auth/register", slug, registrationBody(email)))
                .andReturn().getResponse().getStatus()).isEqualTo(204);
        String otp = otpFor(email);
        assertThat(otp).as("registration OTP captured").isNotNull();
        MvcResult v = mvc.perform(postJson("/api/v1/auth/verify-otp", slug, Map.of("email", email, "otp", otp))).andReturn();
        assertThat(v.getResponse().getStatus()).isEqualTo(200);
        return v;
    }
}
