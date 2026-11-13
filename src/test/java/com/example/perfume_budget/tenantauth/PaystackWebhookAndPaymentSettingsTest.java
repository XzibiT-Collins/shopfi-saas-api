package com.example.perfume_budget.tenantauth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.example.perfume_budget.support.TestTenant;
import com.example.perfume_budget.support.TestTenants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * TENANT-05 AC3 + AC4. Uses FAKE per-tenant Paystack test keys only (never real keys): each tenant's admin saves
 * its own secret via PUT /api/v1/payment-settings, which also mints that tenant's webhook token.
 */
class PaystackWebhookAndPaymentSettingsTest extends AbstractTenantWebTest {

    private static final String SECRET_A = "sk_test_FAKE_acme_" + UUID.randomUUID().toString().replace("-", "");
    private static final String SECRET_B = "sk_test_FAKE_bravo_" + UUID.randomUUID().toString().replace("-", "");

    private String tokenA;
    private String tokenB;
    private String adminCookieA;
    private String adminCookieB;
    private ListAppender<ILoggingEvent> logs;
    private Logger root;

    @BeforeEach
    void configureGatewaysAndCaptureLogs() throws Exception {
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);

        adminCookieA = setCookieValue(loginPassword("acme", acme.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken");
        adminCookieB = setCookieValue(loginPassword("bravo", bravo.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken");
        tokenA = savePaymentSettings("acme", adminCookieA, SECRET_A, "pk_test_FAKE_acme");
        tokenB = savePaymentSettings("bravo", adminCookieB, SECRET_B, "pk_test_FAKE_bravo");
    }

    @AfterEach
    void detachLogs() {
        root.detachAppender(logs);
    }

    // ---- AC3 ----

    @Test
    void ac3_tokensAreDistinctPerTenant() {
        assertThat(tokenA).isNotBlank().isNotEqualTo(tokenB);
    }

    @Test
    void ac3_validSignatureWithTenantAToken_landsInAcmeSchemaOnly() throws Exception {
        String ref = seedPendingPaymentInBothTenants();
        String body = failureEvent(ref);

        MvcResult r = mvc.perform(webhook(tokenA, body, hmac512(body, SECRET_A))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(paymentStatus("tenant_acme", ref)).as("acme payment processed").isEqualTo("FAILED"));
        assertThat(paymentStatus("tenant_bravo", ref)).as("bravo with the same reference is untouched").isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT gateway_response IS NULL FROM tenant_bravo.payments WHERE provider_payment_reference = ?",
                Boolean.class, ref)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_template.payments", Integer.class)).isZero();
    }

    @Test
    void ac3_validSignatureWithTenantBToken_landsInBravoSchemaOnly() throws Exception {
        String ref = seedPendingPaymentInBothTenants();
        String body = failureEvent(ref);

        assertThat(mvc.perform(webhook(tokenB, body, hmac512(body, SECRET_B))).andReturn().getResponse().getStatus()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(paymentStatus("tenant_bravo", ref)).isEqualTo("FAILED"));
        assertThat(paymentStatus("tenant_acme", ref)).isEqualTo("PENDING");
    }

    @Test
    void ac3_unknownTokenIsRejectedAndNothingChanges() throws Exception {
        String ref = seedPendingPaymentInBothTenants();
        String body = failureEvent(ref);
        String sig = hmac512(body, SECRET_A);

        for (String bad : new String[]{"not-a-real-token", tokenA + "x", tokenA.toUpperCase().equals(tokenA) ? tokenA.toLowerCase() : tokenA.toUpperCase()}) {
            MvcResult r = mvc.perform(webhook(bad, body, sig)).andReturn();
            assertThat(r.getResponse().getStatus()).as("token %s", bad).isEqualTo(404);
        }
        assertNothingProcessed(ref);
    }

    @Test
    void ac3_tenantATokenWithTenantBSignatureIsRejected_andViceVersa() throws Exception {
        String ref = seedPendingPaymentInBothTenants();
        String body = failureEvent(ref);

        MvcResult aWithB = mvc.perform(webhook(tokenA, body, hmac512(body, SECRET_B))).andReturn();
        assertThat(aWithB.getResponse().getStatus()).isEqualTo(403);
        MvcResult bWithA = mvc.perform(webhook(tokenB, body, hmac512(body, SECRET_A))).andReturn();
        assertThat(bWithA.getResponse().getStatus()).isEqualTo(403);
        MvcResult junk = mvc.perform(webhook(tokenA, body, "deadbeef")).andReturn();
        assertThat(junk.getResponse().getStatus()).isEqualTo(403);

        assertNothingProcessed(ref);
    }

    @Test
    void ac3_signatureOverDifferentBodyIsRejected() throws Exception {
        String ref = seedPendingPaymentInBothTenants();
        String body = failureEvent(ref);
        String tamperedSig = hmac512(failureEvent(ref + "-other"), SECRET_A);
        assertThat(mvc.perform(webhook(tokenA, body, tamperedSig)).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertNothingProcessed(ref);
    }

    // ---- AC4 ----

    @Test
    void ac4_getPaymentSettingsReturnsSecretConfiguredOnly_neverTheSecret() throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/payment-settings").header("X-Tenant-ID", "acme")
                .cookie(cookie("accessToken", adminCookieA))).andReturn();
        String raw = r.getResponse().getContentAsString();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);

        JsonNode data = json.readTree(raw).get("data");
        assertThat(data.get("secretConfigured").asBoolean()).isTrue();
        assertThat(data.get("publicKey").asText()).isEqualTo("pk_test_FAKE_acme");
        assertThat(data.get("webhookUrl").asText()).endsWith("/api/v1/webhook/" + tokenA);
        assertThat(fieldNames(data)).containsExactlyInAnyOrder("secretConfigured", "publicKey", "enabled", "webhookUrl");

        String encrypted = jdbc.queryForObject("SELECT secret_key_encrypted FROM tenant_acme.payment_gateway_config", String.class);
        assertThat(encrypted).as("secret encrypted at rest").isNotBlank().doesNotContain(SECRET_A);
        assertThat(raw).doesNotContain(SECRET_A).doesNotContain(encrypted).doesNotContain(SECRET_B);
    }

    @Test
    void ac4_tenantsOnlySeeTheirOwnSettings() throws Exception {
        String rawB = mvc.perform(get("/api/v1/payment-settings").header("X-Tenant-ID", "bravo")
                .cookie(cookie("accessToken", adminCookieB))).andReturn().getResponse().getContentAsString();
        assertThat(rawB).contains("pk_test_FAKE_bravo").contains(tokenB)
                .doesNotContain("pk_test_FAKE_acme").doesNotContain(tokenA).doesNotContain(SECRET_A).doesNotContain(SECRET_B);
    }

    @Test
    void ac4_getPaymentSettingsRequiresAdminAuthentication() throws Exception {
        assertThat(mvc.perform(get("/api/v1/payment-settings").header("X-Tenant-ID", "acme")).andReturn()
                .getResponse().getStatus()).isIn(401, 403);
        // acme admin cookie replayed on bravo must not expose bravo's settings
        MvcResult cross = mvc.perform(get("/api/v1/payment-settings").header("X-Tenant-ID", "bravo")
                .cookie(cookie("accessToken", adminCookieA))).andReturn();
        assertThat(cross.getResponse().getStatus()).isEqualTo(401);
        assertThat(cross.getResponse().getContentAsString()).doesNotContain("pk_test_FAKE_bravo");
    }

    @Test
    void ac4_secretNeverAppearsInLogs_acrossSaveGetAndWebhookTraffic() throws Exception {
        String ref = seedPendingPaymentInBothTenants();
        String body = failureEvent(ref);
        // re-save (logs the save path again), read, and drive good + bad webhooks (which decrypt the secret)
        savePaymentSettings("acme", adminCookieA, SECRET_A, "pk_test_FAKE_acme");
        mvc.perform(get("/api/v1/payment-settings").header("X-Tenant-ID", "acme").cookie(cookie("accessToken", adminCookieA)));
        mvc.perform(webhook(tokenA, body, hmac512(body, SECRET_A)));
        mvc.perform(webhook(tokenA, body, hmac512(body, SECRET_B)));
        mvc.perform(webhook(tokenB, body, hmac512(body, SECRET_B)));
        mvc.perform(webhook("bogus", body, "x"));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(paymentStatus("tenant_acme", ref)).isEqualTo("FAILED"));
        Thread.sleep(500); // let async webhook workers finish logging

        String encryptedA = jdbc.queryForObject("SELECT secret_key_encrypted FROM tenant_acme.payment_gateway_config", String.class);
        String encryptedB = jdbc.queryForObject("SELECT secret_key_encrypted FROM tenant_bravo.payment_gateway_config", String.class);
        String all = logs.list.stream().map(this::render).collect(Collectors.joining("\n"));
        assertThat(logs.list).as("log capture is live").isNotEmpty();
        assertThat(all).as("plaintext secret A in logs").doesNotContain(SECRET_A);
        assertThat(all).as("plaintext secret B in logs").doesNotContain(SECRET_B);
        assertThat(all).as("encrypted secret A in logs").doesNotContain(encryptedA);
        assertThat(all).as("encrypted secret B in logs").doesNotContain(encryptedB);
    }

    /** PaymentSettingsRequest documents "secretKey may be blank on update to leave the existing secret unchanged". */
    @Test
    void ac4_updateWithBlankSecretKeepsExistingSecret() throws Exception {
        String before = jdbc.queryForObject("SELECT secret_key_encrypted FROM tenant_acme.payment_gateway_config", String.class);
        MvcResult r = mvc.perform(put("/api/v1/payment-settings").contentType("application/json").header("X-Tenant-ID", "acme")
                .cookie(cookie("accessToken", adminCookieA))
                .content(json.writeValueAsString(Map.of("secretKey", "", "publicKey", "pk_test_FAKE_acme2", "enabled", true)))).andReturn();
        assertThat(r.getResponse().getStatus()).as("documented contract: blank secret leaves it unchanged").isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT secret_key_encrypted FROM tenant_acme.payment_gateway_config", String.class)).isEqualTo(before);
    }

    // ---- helpers ----

    private String render(ILoggingEvent e) {
        String t = e.getThrowableProxy() == null ? "" : "\n" + ThrowableProxyUtil.asString(e.getThrowableProxy());
        return e.getFormattedMessage() + t;
    }

    private static java.util.List<String> fieldNames(JsonNode n) {
        var out = new java.util.ArrayList<String>();
        n.fieldNames().forEachRemaining(out::add);
        return out;
    }

    private String savePaymentSettings(String slug, String adminToken, String secret, String publicKey) throws Exception {
        MockHttpServletRequestBuilder req = put("/api/v1/payment-settings")
                .contentType("application/json").header("X-Tenant-ID", slug)
                .cookie(cookie("accessToken", adminToken))
                .content(json.writeValueAsString(Map.of("secretKey", secret, "publicKey", publicKey, "enabled", true)));
        MvcResult r = mvc.perform(req).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        String url = json.readTree(r.getResponse().getContentAsString()).at("/data/webhookUrl").asText();
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private MockHttpServletRequestBuilder webhook(String token, String body, String signature) {
        // No Origin header and no tenant header: the token alone must select the tenant.
        return post("/api/v1/webhook/" + token).contentType("application/json")
                .header("X-Paystack-Signature", signature).content(body);
    }

    private static String failureEvent(String reference) {
        return "{\"event\":\"charge.failed\",\"data\":{\"id\":1,\"reference\":\"" + reference
                + "\",\"status\":\"failed\",\"message\":\"declined\",\"channel\":\"card\",\"currency\":\"GHS\"}}";
    }

    private static String hmac512(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        StringBuilder sb = new StringBuilder();
        for (byte b : mac.doFinal(data.getBytes(StandardCharsets.UTF_8))) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** Same provider reference, PENDING, in both tenants, so the schema that processes it is observable. */
    private String seedPendingPaymentInBothTenants() {
        String ref = "ref-" + UUID.randomUUID();
        for (TestTenant t : new TestTenant[]{acme, bravo}) {
            String s = t.schemaName();
            String orderNo = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            Long orderId = jdbc.queryForObject("INSERT INTO " + s + ".orders (order_number, status, delivery_status, created_at) "
                    + "VALUES (?, 'PENDING', 'PENDING', now()) RETURNING id", Long.class, orderNo);
            jdbc.update("INSERT INTO " + s + ".payments (system_reference, provider_payment_reference, order_id, provider, amount, currency, "
                    + "status, customer_email, created_at) VALUES (?, ?, ?, 'PAYSTACK', 10.00, 'GHS', 'PENDING', 'c@example.com', now())",
                    "sys-" + UUID.randomUUID(), ref, orderId);
        }
        return ref;
    }

    private String paymentStatus(String schema, String ref) {
        return jdbc.queryForObject("SELECT status FROM " + schema + ".payments WHERE provider_payment_reference = ?", String.class, ref);
    }

    private void assertNothingProcessed(String ref) throws InterruptedException {
        Thread.sleep(1000); // rejected requests never dispatch async work; give any wrongly-dispatched task time to show
        assertThat(paymentStatus("tenant_acme", ref)).isEqualTo("PENDING");
        assertThat(paymentStatus("tenant_bravo", ref)).isEqualTo("PENDING");
    }
}
