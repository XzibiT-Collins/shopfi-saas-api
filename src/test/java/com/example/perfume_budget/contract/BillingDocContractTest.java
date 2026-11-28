package com.example.perfume_budget.contract;

import com.example.perfume_budget.contract.BillingDocExamples.Example;
import com.example.perfume_budget.exception.handlers.GlobalExceptionHandler;
import com.example.perfume_budget.platform.billing.AbstractBillingIntegrationTest;
import com.example.perfume_budget.platform.billing.BillingClock;
import com.example.perfume_budget.platform.billing.IdleBillingRound;
import com.example.perfume_budget.platform.billing.dev.DevBillingController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * BILL-01 (billing.md section 21 item 13): the examples of docs/api/billing.md that belong to BILL-01 (settings, number
 * preview, credentials, settings audit and the dev billing clock) against the REAL server on PostgreSQL and the fake
 * Paystack. Every response example is produced by its scenario and must validate against its schema and equal the live body
 * (description, data keys, data values; timestamps and the admin e-mail are normalised). Every {@code valid} request example
 * is accepted and every {@code invalid} one rejected with its status. Examples that need state of a LATER ticket are listed in
 * {@link #LATER_TICKETS} and are only schema-checked here ({@link BillingDocSchemaTest}).
 */
class BillingDocContractTest extends AbstractBillingIntegrationTest {

    static final String DOC_ADMIN = "admin@shopfi.test";
    /** Examples whose scenario needs BILL-01b / BILL-02 state (billing-managed subscriptions, billing rounds). */
    static final Set<String> LATER_TICKETS = Set.of("settings-health-paused", "dev-billing-run-result");
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String QX_PK = "pk_test_DOCSEXAMPLEONLYNOTAKEYQx7Z";
    static final String QX_SK = "sk_test_DOCSEXAMPLEONLYNOTAKEYk3Pd";

    private final BillingDocExamples docs = new BillingDocExamples();
    private final Set<String> exercised = new LinkedHashSet<>();

    // ------------------------------------------------------------------------------------------ helpers

    private String req(String name) {
        return docs.get(name).json().toString();
    }

    /** Status, schema, description and data must agree. */
    private void assertMatches(String name, MockHttpServletResponse r) throws Exception {
        Example ex = docs.get(name);
        String raw = r.getContentAsString().replace(admin.getEmail(), DOC_ADMIN);
        assertThat(r.getStatus()).as(name + ": " + raw).isEqualTo(ex.status());
        JsonNode live = MAPPER.readTree(raw);
        assertThat(docs.validate(ex.schema(), live)).as(name + " violates " + ex.schema() + ": " + raw).isEmpty();
        JsonNode expected = ex.json().deepCopy();
        normalise(expected);
        normalise(live);
        assertThat(live).as(name).isEqualTo(expected);
        exercised.add(name);
    }

    /** Values a live run cannot reproduce: instants (and the webhook timestamps, which only the receiver of BILL-01b sets). */
    private static void normalise(JsonNode n) {
        if (n.isObject()) {
            ObjectNode o = (ObjectNode) n;
            for (String f : List.of("updatedAt", "occurredAt", "since", "lastWebhookAt", "lastWebhookOkAt", "id", "actorId")) {
                if (o.has(f) && !o.get(f).isNull()) {
                    o.put(f, "<normalised>");
                } else if (o.has(f) && (f.equals("lastWebhookAt") || f.equals("lastWebhookOkAt"))) {
                    o.put(f, "<normalised>");
                }
            }
            if (o.has("verifiedAt") && !o.get("verifiedAt").isNull()) {
                o.put("verifiedAt", "<normalised>");
            }
            o.elements().forEachRemaining(BillingDocContractTest::normalise);
        } else if (n.isArray()) {
            n.forEach(BillingDocContractTest::normalise);
        }
    }

    /** The billing clock in 2026 so the number previews of the examples (SUB-2026-000001) are reproducible. */
    private void in2026() {
        billingClock.moveTo(Instant.parse("2026-12-01T00:00:00Z"));
    }

    /** TEST credentials with the last 4 of the examples (Qx7Z / k3Pd), written twice (version 2) and verified. */
    private void verifiedTestCredentials() throws Exception {
        assertThat(putCredentials("test", QX_PK, "sk_test_DOCSEXAMPLEONLYNOTAKEY0000").getStatus()).isEqualTo(200);
        assertThat(putCredentials("test", QX_PK, QX_SK).getStatus()).isEqualTo(200);
        assertThat(verifyCredentials("test").getStatus()).isEqualTo(200);
    }

    /** The state of settings-configured: settings version 4, billing on, grace 10 with four retries, TEST verified. */
    private void configuredState() throws Exception {
        in2026();
        verifiedTestCredentials();
        exec("UPDATE master.billing_settings SET version = 4, billing_enabled = TRUE, grace_days = 10, "
                + "retry_offsets_days = '[1,3,5,8]'::jsonb, updated_at = now(), updated_by_email = ?", admin.getEmail());
    }

    // ------------------------------------------------------------------------------------------ settings

    @Test
    void settingsDefaults() throws Exception {
        in2026();
        assertMatches("settings-defaults", asAdmin(get(SETTINGS), null));
    }

    @Test
    void settingsConfigured_update_andTheVersionConflict() throws Exception {
        configuredState();
        assertMatches("settings-configured", asAdmin(get(SETTINGS), null));
        assertMatches("credentials-list", asAdmin(get(CREDS), null));

        // the valid request example (version 4) is accepted and answers the update example (version 5)
        assertMatches("settings-update-response", asAdmin(put(SETTINGS).contentType("application/json"), req("settings-update-request")));
        exercised.add("settings-update-request");
        // the same version again: another admin got there first
        assertMatches("settings-version-conflict", asAdmin(put(SETTINGS).contentType("application/json"), req("settings-update-request")));
    }

    @Test
    void everyInvalidSettingsExampleIsRejectedWith400_andNothingIsWritten() throws Exception {
        in2026();
        long audit = settingsAuditRows();
        for (String name : List.of("settings-invalid-grace-days", "settings-invalid-duplicate-retry-days", "settings-invalid-number-format",
                "settings-invalid-tax-rate", "settings-invalid-verification-amount")) {
            MockHttpServletResponse r = asAdmin(put(SETTINGS).contentType("application/json"), req(name));
            assertThat(r.getStatus()).as(name + ": " + r.getContentAsString()).isEqualTo(docs.get(name).status());
            JsonNode b = body(r);
            assertThat(docs.validate("ValidationErrorEnvelope", b)).as(name).isEmpty();
            exercised.add(name);
        }
        assertThat(settingsAuditRows()).isEqualTo(audit);
        assertThat(scalar("SELECT version FROM master.billing_settings")).isZero();
    }

    @Test
    void settingsValidationError() throws Exception {
        in2026();
        ObjectNode b = ((ObjectNode) docs.get("settings-update-request").json().deepCopy());
        b.put("version", 0);
        b.put("billingEnabled", false);
        b.putArray("retryOffsetsDays").add(1).add(3).add(5).add(9); // 9 > graceDays 7
        b.put("activeEnvironment", "LIVE");                          // LIVE has no verified credentials
        assertMatches("settings-validation-error", putSettings(b));
    }

    @Test
    void numberPreview() throws Exception {
        in2026();
        assertMatches("number-preview-response", asAdmin(post(SETTINGS + "/number-preview").contentType("application/json"), req("number-preview-request")));
        exercised.add("number-preview-request");
        MockHttpServletResponse bad = asAdmin(post(SETTINGS + "/number-preview").contentType("application/json"), req("number-preview-request-no-counter"));
        assertThat(bad.getStatus()).isEqualTo(docs.get("number-preview-request-no-counter").status());
        assertThat(docs.validate("ValidationErrorEnvelope", body(bad))).isEmpty();
        exercised.add("number-preview-request-no-counter");
    }

    @Test
    void settingsAuditPage() throws Exception {
        in2026();
        clearAudit(); // the scenario starts from an empty audit: the example page has exactly the two rows below
        exec("UPDATE master.billing_settings SET version = 4, grace_days = 10, retry_offsets_days = '[1,3,5,8]'::jsonb");
        // credentials written at settings version 4
        assertThat(putCredentials("test", QX_PK, QX_SK).getStatus()).isEqualTo(200);
        // settings save 5: grace 10 -> 7, retries [1,3,5,8] -> [1,3,5], business name and tax rate set
        ObjectNode b = settingsBody();
        b.put("graceDays", 7);
        b.putArray("retryOffsetsDays").add(1).add(3).add(5);
        b.put("businessName", "Example Business Ltd");
        b.put("taxRateBps", 1500);
        assertThat(putSettings(b).getStatus()).isEqualTo(200);
        assertMatches("settings-audit-page", asAdmin(get(SETTINGS + "/audit"), null));
    }

    private void verifiedTestCredentialsQuietly() throws Exception {
        assertThat(putCredentials("test", QX_PK, QX_SK).getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------------------------------ credentials

    @Test
    void credentialsSavedAndTheRequestExamples() throws Exception {
        MockHttpServletResponse bad = asAdmin(put(CREDS + "/test").contentType("application/json"), req("credentials-request-bad-key"));
        assertThat(bad.getStatus()).isEqualTo(docs.get("credentials-request-bad-key").status());
        assertThat(docs.validate("ValidationErrorEnvelope", body(bad))).isEmpty();
        exercised.add("credentials-request-bad-key");
        assertMatches("credentials-saved", asAdmin(put(CREDS + "/test").contentType("application/json"), req("credentials-request-test")));
        exercised.add("credentials-request-test");
    }

    @Test
    void credentialsPrefixMismatch() throws Exception {
        String liveSecret = "sk_" + "live_" + "DOCSEXAMPLEONLYNOTAKEY0002";
        assertMatches("credentials-prefix-mismatch", asAdmin(put(CREDS + "/test").contentType("application/json"),
                "{\"publicKey\":\"" + TEST_PK + "\",\"secretKey\":\"" + liveSecret + "\"}"));
    }

    @Test
    void credentialsVersionConflict() throws Exception {
        assertThat(putCredentials("test", TEST_PK, TEST_SK).getStatus()).isEqualTo(200);
        assertThat(putCredentials("test", TEST_PK, TEST_SK).getStatus()).isEqualTo(200);
        assertMatches("credentials-version-conflict", asAdmin(put(CREDS + "/test").contentType("application/json"),
                "{\"publicKey\":\"" + TEST_PK + "\",\"secretKey\":\"" + TEST_SK + "\",\"expectedVersion\":1}"));
    }

    @Test
    void credentialsVerified_failed_notConfigured_andProviderUnavailable() throws Exception {
        assertMatches("credentials-not-configured", verifyCredentials("live"));

        verifiedTestCredentialsQuietly();
        putCredentials("test", QX_PK, "sk_test_DOCSEXAMPLEONLYNOTAKEY0000"); // version 2 like the example
        putCredentials("test", QX_PK, QX_SK);
        assertThat(scalar("SELECT version FROM master.billing_paystack_credentials WHERE environment = 'TEST'")).isEqualTo(3);
        resetBillingTables();
        putCredentials("test", QX_PK, "sk_test_DOCSEXAMPLEONLYNOTAKEY0000");
        putCredentials("test", QX_PK, QX_SK);
        assertMatches("credentials-verified", verifyCredentials("test"));

        // LIVE with a key Paystack rejects
        fake.acceptSecret("sk_" + "live_" + "someOtherKeyThatIsAccepted0001");
        assertThat(putCredentials("live", "pk_" + "live_" + "DOCSEXAMPLEONLYNOTAKEY0001", "sk_" + "live_" + "DOCSEXAMPLEONLYNOTAKEY0002").getStatus()).isEqualTo(200);
        assertMatches("credentials-verify-failed", verifyCredentials("live"));

        // an outage on TEST
        fake.reset();
        fake.outage(500);
        assertMatches("credentials-provider-unavailable", verifyCredentials("test"));
        fake.outage(0);
    }

    // ------------------------------------------------------------------------------------------ dev billing clock (profile dev only)

    @Test
    void devClockExamples() throws Exception {
        // base 2026-11-27T09:30:00Z + 1209540 s = 2026-12-11T09:29:00Z (the dev-clock example)
        Clock base = Clock.fixed(Instant.parse("2026-11-27T09:30:00Z"), ZoneOffset.UTC);
        BillingClock billing = new BillingClock(base);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DevBillingController(billing, new IdleBillingRound()))
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                        org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        billing.advanceSeconds(1_209_540);
        assertMatches("dev-clock", mvc.perform(get("/api/v1/dev/billing/clock")).andReturn().getResponse());

        billing.reset();
        MockHttpServletResponse sec = mvc.perform(post("/api/v1/dev/billing/clock/advance").contentType("application/json").content(req("dev-clock-advance-seconds"))).andReturn().getResponse();
        assertThat(sec.getStatus()).isEqualTo(200);
        assertThat(docs.validate("DevClockEnvelope", MAPPER.readTree(sec.getContentAsString()))).isEmpty();
        assertThat(billing.offsetSeconds()).isEqualTo(86400);
        exercised.add("dev-clock-advance-seconds");

        MockHttpServletResponse to = mvc.perform(post("/api/v1/dev/billing/clock/advance").contentType("application/json").content(req("dev-clock-advance-to"))).andReturn().getResponse();
        assertThat(to.getStatus()).isEqualTo(200);
        assertThat(billing.now()).isEqualTo(Instant.parse("2026-12-11T09:30:00Z"));
        exercised.add("dev-clock-advance-to");

        MockHttpServletResponse reset = mvc.perform(post("/api/v1/dev/billing/clock/advance").contentType("application/json").content(req("dev-clock-reset"))).andReturn().getResponse();
        assertThat(reset.getStatus()).isEqualTo(200);
        assertThat(billing.offsetSeconds()).isZero();
        exercised.add("dev-clock-reset");

        MockHttpServletResponse zero = mvc.perform(post("/api/v1/dev/billing/clock/advance").contentType("application/json").content(req("dev-clock-advance-zero"))).andReturn().getResponse();
        assertThat(zero.getStatus()).isEqualTo(docs.get("dev-clock-advance-zero").status());
        exercised.add("dev-clock-advance-zero");
        assertThat(billing.offsetSeconds()).isZero();
    }

    // ------------------------------------------------------------------------------------------ completeness

    /**
     * Runs every scenario of this class in one test and then proves that EVERY BILL-01 example of the document was exercised
     * (a new example under these prefixes without a scenario fails here) or is explicitly a later ticket's.
     */
    @Test
    void everyBill01ExampleHasAScenario() throws Exception {
        settingsDefaults();
        resetBillingTables();
        settingsConfigured_update_andTheVersionConflict();
        resetBillingTables();
        everyInvalidSettingsExampleIsRejectedWith400_andNothingIsWritten();
        settingsValidationError();
        numberPreview();
        settingsAuditPage();
        resetBillingTables();
        credentialsSavedAndTheRequestExamples();
        resetBillingTables();
        credentialsPrefixMismatch();
        resetBillingTables();
        credentialsVersionConflict();
        resetBillingTables();
        credentialsVerified_failed_notConfigured_andProviderUnavailable();
        devClockExamples();

        Set<String> bill01 = new TreeSet<>();
        for (String name : docs.examples.keySet()) {
            if (name.startsWith("settings-") || name.startsWith("credentials-") || name.startsWith("number-preview-") || name.startsWith("dev-")) {
                bill01.add(name);
            }
        }
        assertThat(bill01).as("BILL-01 examples of the document").hasSizeGreaterThanOrEqualTo(30);
        Set<String> unexercised = new TreeSet<>(bill01);
        unexercised.removeAll(exercised);
        unexercised.removeAll(LATER_TICKETS);
        assertThat(unexercised).as("examples with no live scenario").isEmpty();
        assertThat(LATER_TICKETS).isSubsetOf(bill01);
    }
}
