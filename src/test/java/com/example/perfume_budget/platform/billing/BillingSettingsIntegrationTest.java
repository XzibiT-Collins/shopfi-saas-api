package com.example.perfume_budget.platform.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** BILL-01 AC2 / AC8: the settings API on PostgreSQL: version, audit, validation, optimistic lock, number preview. */
class BillingSettingsIntegrationTest extends AbstractBillingIntegrationTest {

    private JsonNode data(MockHttpServletResponse r) throws Exception {
        return body(r).get("data");
    }

    @Test
    void getReturnsTheDocumentedDefaultsAndTwoUnsetEnvironments() throws Exception {
        JsonNode d = data(asAdmin(get(SETTINGS), null));
        JsonNode s = d.get("settings");
        assertThat(d.get("version").asInt()).isZero();
        assertThat(d.get("updatedAt").isNull()).isTrue();
        assertThat(d.get("updatedBy").isNull()).isTrue();
        assertThat(s.get("billingEnabled").asBoolean()).isFalse();
        assertThat(s.get("activeEnvironment").asText()).isEqualTo("TEST");
        assertThat(s.get("cardRequiredAtSignup").asBoolean()).isTrue();
        assertThat(s.get("graceDays").asInt()).isEqualTo(7);
        assertThat(s.get("retryOffsetsDays").toString()).isEqualTo("[1,3,5]");
        assertThat(s.get("retryTimeOfDayUtc").isNull()).isTrue();
        assertThat(s.get("trialReminderDaysBefore").toString()).isEqualTo("[3,1]");
        assertThat(s.get("renewalReminderDaysBefore").toString()).isEqualTo("[3]");
        assertThat(s.get("priceChangeNoticeDays").asInt()).isEqualTo(30);
        assertThat(s.get("graceReminderOffsetsDays").toString()).isEqualTo("[1,3,5]");
        assertThat(s.get("graceEndingReminderDaysBefore").toString()).isEqualTo("[1]");
        assertThat(s.get("emailEnabled")).hasSize(27);
        s.get("emailEnabled").forEach(v -> assertThat(v.asBoolean()).isTrue());
        assertThat(s.get("invoiceNumberFormat").asText()).isEqualTo("SUB-{YYYY}-{NNNNNN}");
        assertThat(s.get("receiptNumberFormat").asText()).isEqualTo("REC-{YYYY}-{NNNNNN}");
        assertThat(s.get("creditNoteNumberFormat").asText()).isEqualTo("CN-{YYYY}-{NNNNNN}");
        for (String f : List.of("businessName", "businessAddress", "businessTin", "businessEmail", "businessPhone", "taxLabel", "taxRateBps", "invoiceFooterText")) {
            assertThat(s.get(f).isNull()).as(f + " is empty until the owner supplies it").isTrue();
        }
        assertThat(s.get("taxInclusive").asBoolean()).isTrue();
        assertThat(s.get("cardVerificationAmountPesewas").asLong()).isEqualTo(100);
        assertThat(s.get("allowedChannels").toString()).isEqualTo("[\"card\"]");
        assertThat(s.get("recurringChannels").toString()).isEqualTo("[\"card\"]");
        assertThat(s.get("oneTrialPerCard").asBoolean()).isTrue();
        assertThat(s.get("refundMaxDays").asInt()).isEqualTo(90);
        assertThat(s.get("refundConfirmThresholdPesewas").asLong()).isEqualTo(100000);
        assertThat(d.get("credentials")).hasSize(2);
        assertThat(d.get("credentials").get(0).get("environment").asText()).isEqualTo("TEST");
        assertThat(d.get("credentials").get(1).get("environment").asText()).isEqualTo("LIVE");
        assertThat(d.get("credentials").get(0).get("status").asText()).isEqualTo("UNSET");
        assertThat(d.get("health").toString()).isEqualTo("{\"paused\":false,\"reason\":null,\"since\":null,\"affectedSubscriptions\":0}");
    }

    @Test
    void putSavesBumpsTheVersionStampsTheActorAndWritesOneAuditRowWithFieldLevelBeforeAfter() throws Exception {
        long auditBefore = settingsAuditRows();
        ObjectNode b = settingsBody();
        b.put("graceDays", 10);
        ((ArrayNode) b.get("retryOffsetsDays")).add(8);
        b.put("businessName", "  Example Business Ltd ");
        b.put("taxRateBps", 1500);
        ((ObjectNode) b.get("emailEnabled")).put("TRIAL_REMINDER", false);
        MockHttpServletResponse r = putSettings(b);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        JsonNode d = data(r);
        assertThat(d.get("version").asInt()).isEqualTo(1);
        assertThat(d.get("updatedBy").asText()).isEqualTo(admin.getEmail());
        assertThat(d.get("updatedAt").asText()).endsWith("Z");
        assertThat(d.get("settings").get("graceDays").asInt()).isEqualTo(10);
        assertThat(d.get("settings").get("businessName").asText()).isEqualTo("Example Business Ltd");
        assertThat(data(asAdmin(get(SETTINGS), null)).get("settings").get("retryOffsetsDays").toString()).isEqualTo("[1,3,5,8]");

        assertThat(settingsAuditRows()).isEqualTo(auditBefore + 1);
        JsonNode row = data(asAdmin(get(SETTINGS + "/audit"), null)).get("items").get(0);
        assertThat(row.get("actorEmail").asText()).isEqualTo(admin.getEmail());
        assertThat(row.get("actorId").asLong()).isEqualTo(admin.getId());
        assertThat(row.get("settingsVersion").asInt()).isEqualTo(1);
        JsonNode changes = row.get("changes");
        assertThat(changes).hasSize(5);
        assertThat(change(changes, "graceDays").toString()).isEqualTo("{\"field\":\"graceDays\",\"before\":7,\"after\":10}");
        assertThat(change(changes, "retryOffsetsDays").toString()).isEqualTo("{\"field\":\"retryOffsetsDays\",\"before\":[1,3,5],\"after\":[1,3,5,8]}");
        assertThat(change(changes, "businessName").toString()).isEqualTo("{\"field\":\"businessName\",\"before\":null,\"after\":\"Example Business Ltd\"}");
        assertThat(change(changes, "taxRateBps").toString()).isEqualTo("{\"field\":\"taxRateBps\",\"before\":null,\"after\":1500}");
        assertThat(change(changes, "emailEnabled.TRIAL_REMINDER").toString()).isEqualTo("{\"field\":\"emailEnabled.TRIAL_REMINDER\",\"before\":true,\"after\":false}");
    }

    private static JsonNode change(JsonNode changes, String field) {
        for (JsonNode c : changes) {
            if (c.get("field").asText().equals(field)) {
                return c;
            }
        }
        throw new AssertionError("no change for " + field + " in " + changes);
    }

    @Test
    void aBodyEqualToTheStoredValuesWritesNothing_noBumpNoAuditRow() throws Exception {
        long audit = settingsAuditRows();
        MockHttpServletResponse r = putSettings(settingsBody());
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(data(r).get("version").asInt()).isZero();
        assertThat(settingsAuditRows()).isEqualTo(audit);
        // ... but it is still version-checked
        ObjectNode stale = settingsBody();
        stale.put("version", 5);
        assertThat(putSettings(stale).getStatus()).isEqualTo(409);
    }

    @Test
    void aStaleVersionIs409WithBothVersions_neverThe503TryAgain_andNothingIsWritten() throws Exception {
        ObjectNode first = settingsBody();
        first.put("graceDays", 9);
        assertThat(putSettings(first).getStatus()).isEqualTo(200);
        long audit = settingsAuditRows();
        ObjectNode stale = settingsBody();
        stale.put("version", 0);
        stale.put("graceDays", 11);
        MockHttpServletResponse r = putSettings(stale);
        assertThat(r.getStatus()).isEqualTo(409);
        JsonNode b = body(r);
        assertThat(b.get("description").asText()).isEqualTo("Billing settings were changed by someone else. Reload and review.");
        assertThat(b.get("data")).isEqualTo(JSON.readTree("{\"code\":\"BILLING_SETTINGS_VERSION_CONFLICT\",\"expectedVersion\":0,\"currentVersion\":1}"));
        assertThat(settingsAuditRows()).isEqualTo(audit);
        assertThat(data(asAdmin(get(SETTINGS), null)).get("settings").get("graceDays").asInt()).isEqualTo(9);
        // a version from the future is stale too
        ObjectNode future = settingsBody();
        future.put("version", 99);
        assertThat(putSettings(future).getStatus()).isEqualTo(409);
    }

    @Test
    void twoWritersFromTheSameVersion_exactlyOneWins_theOtherIs409_neverA500Or503() throws Exception {
        for (int round = 0; round < 5; round++) {
            resetBillingTables();
            ObjectNode a = settingsBody();
            a.put("graceDays", 8);
            ObjectNode b = settingsBody();
            b.put("graceDays", 9);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (ObjectNode body : List.of(a, b)) {
                Callable<Integer> c = () -> {
                    go.await();
                    return putSettings(body).getStatus();
                };
                results.add(pool.submit(c));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get(30, TimeUnit.SECONDS));
            }
            pool.shutdownNow();
            assertThat(statuses).as("round " + round).containsExactlyInAnyOrder(200, 409);
            assertThat(scalar("SELECT version FROM master.billing_settings")).isEqualTo(1);
        }
    }

    @Test
    void validationReportsEveryFieldAtOnce_nothingIsWritten() throws Exception {
        long audit = settingsAuditRows();
        ObjectNode b = settingsBody();
        b.put("graceDays", 61);
        b.put("taxRateBps", 10001);
        b.put("cardVerificationAmountPesewas", 50);
        b.put("invoiceNumberFormat", "INV-{YYYY}");
        b.put("businessName", "evil‮name");
        b.put("businessEmail", "nope");
        MockHttpServletResponse r = putSettings(b);
        assertThat(r.getStatus()).isEqualTo(400);
        JsonNode j = body(r);
        assertThat(j.get("description").asText()).isEqualTo("Validation failed");
        assertThat(j.get("data").fieldNames()).toIterable().containsExactlyInAnyOrder("graceDays", "taxRateBps",
                "cardVerificationAmountPesewas", "invoiceNumberFormat", "businessName", "businessEmail");
        assertThat(j.at("/data/graceDays").asText()).isEqualTo("must be between 0 and 60");
        assertThat(j.at("/data/taxRateBps").asText()).isEqualTo("must be between 0 and 10000");
        assertThat(j.at("/data/invoiceNumberFormat").asText()).isEqualTo("must contain {NNNNNN} and only letters, digits and - / _ . { }");
        assertThat(scalar("SELECT version FROM master.billing_settings")).isZero();
        assertThat(settingsAuditRows()).isEqualTo(audit);
    }

    @Test
    void validationPrecedesTheVersionCheck_aBadBodyWithAStaleVersionIs400() throws Exception {
        ObjectNode b = settingsBody();
        b.put("version", 7);
        b.put("graceDays", 99);
        assertThat(putSettings(b).getStatus()).isEqualTo(400);
    }

    @Test
    void retryOffsetsAreCheckedAgainstTheGraceDaysAndOrdering() throws Exception {
        ObjectNode b = settingsBody();
        b.put("graceDays", 4);
        MockHttpServletResponse r = putSettings(b); // [1,3,5] with grace 4: 5 > 4
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(body(r).at("/data/retryOffsetsDays").asText()).isEqualTo("every retry day must be <= graceDays and the list must be strictly ascending");
        b.set("retryOffsetsDays", mapper().createArrayNode().add(1).add(3));
        assertThat(putSettings(b).getStatus()).isEqualTo(200);
        ObjectNode zero = settingsBody();
        zero.put("graceDays", 0);
        zero.set("retryOffsetsDays", mapper().createArrayNode());
        assertThat(putSettings(zero).getStatus()).as("grace 0 with no retries").isEqualTo(200);
    }

    private static com.fasterxml.jackson.databind.ObjectMapper mapper() {
        return JSON;
    }

    @Test
    void textRules_blankBecomesNull_trimmed_controlAndFormatCharactersRefused() throws Exception {
        ObjectNode b = settingsBody();
        b.put("businessName", "   Example　 ");
        b.put("businessAddress", "   ");
        MockHttpServletResponse ok = putSettings(b);
        assertThat(ok.getStatus()).as(ok.getContentAsString()).isEqualTo(200);
        assertThat(data(ok).at("/settings/businessName").asText()).isEqualTo("Example");
        assertThat(data(ok).at("/settings/businessAddress").isNull()).isTrue();
        for (String bad : List.of("a\tb", "a\nb", "a\u0085b", "a‮b", "a​b", "a b", "a﻿b", "a­b")) {
            ObjectNode c = settingsBody();
            c.put("invoiceFooterText", bad);
            assertThat(putSettings(c).getStatus()).as("U+" + Integer.toHexString(bad.charAt(1))).isEqualTo(400);
        }
    }

    @Test
    void wrongTypesMissingFieldsAndNonObjectBodiesAre400() throws Exception {
        ObjectNode b = settingsBody();
        b.put("graceDays", "7");
        assertThat(putSettings(b).getStatus()).isEqualTo(400);
        ObjectNode missing = settingsBody();
        missing.remove("emailEnabled");
        missing.remove("businessName");
        MockHttpServletResponse r = putSettings(missing);
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(body(r).get("data").fieldNames()).toIterable().contains("emailEnabled", "businessName");
        for (String raw : List.of("[]", "5", "\"x\"", "null", "{")) {
            assertThat(asAdmin(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(SETTINGS)
                    .contentType("application/json"), raw).getStatus()).as(raw).isEqualTo(400);
        }
        assertThat(asAdmin(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(SETTINGS)
                .contentType("application/json"), null).getStatus()).isEqualTo(400);
        assertThat(scalar("SELECT version FROM master.billing_settings")).isZero();
    }

    @Test
    void unknownPropertiesAreIgnored() throws Exception {
        ObjectNode b = settingsBody();
        b.put("somethingElse", "x");
        b.put("updatedBy", "attacker@example.com").put("credentials", "nope");
        b.put("graceDays", 9);
        assertThat(putSettings(b).getStatus()).isEqualTo(200);
        assertThat(data(asAdmin(get(SETTINGS), null)).get("updatedBy").asText()).isEqualTo(admin.getEmail());
    }

    // ------------------------------------------------------------------------------------------ activation rules

    @Test
    void anEnvironmentCanOnlyBeActiveOrBillingEnabledWithVerifiedCredentials() throws Exception {
        // billing on with nothing verified
        ObjectNode on = settingsBody();
        on.put("billingEnabled", true);
        MockHttpServletResponse r = putSettings(on);
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(body(r).at("/data/billingEnabled").asText()).isEqualTo("billing cannot be enabled until the active environment's credentials are verified");
        // switching to LIVE with LIVE unset
        ObjectNode live = settingsBody();
        live.put("activeEnvironment", "LIVE");
        MockHttpServletResponse l = putSettings(live);
        assertThat(l.getStatus()).isEqualTo(400);
        assertThat(body(l).at("/data/activeEnvironment").asText()).isEqualTo("the environment's credentials are not verified");
        // UNVERIFIED is not enough
        assertThat(putCredentials("live", "pk_" + "live_" + "DOCSEXAMPLEONLYNOTAKEY0001", "sk_" + "live_" + "DOCSEXAMPLEONLYNOTAKEY0002").getStatus()).isEqualTo(200);
        assertThat(putSettings(live).getStatus()).isEqualTo(400);
        // verified: both are accepted
        assertThat(verifyCredentials("live").getStatus()).isEqualTo(200);
        live.put("billingEnabled", true);
        MockHttpServletResponse ok = putSettings(live);
        assertThat(ok.getStatus()).as(ok.getContentAsString()).isEqualTo(200);
        assertThat(data(ok).at("/settings/activeEnvironment").asText()).isEqualTo("LIVE");
        assertThat(data(ok).at("/settings/billingEnabled").asBoolean()).isTrue();
        // once on, saving unrelated settings is not blocked even if the keys fail later
        exec("UPDATE master.billing_paystack_credentials SET status = 'FAILED', failure_code = 'KEY_REJECTED' WHERE environment = 'LIVE'");
        ObjectNode other = settingsBody();
        other.put("graceDays", 9);
        assertThat(putSettings(other).getStatus()).isEqualTo(200);
    }

    @Test
    void theUntouchedDefaultEnvironmentDoesNotBlockSavingOtherSettings() throws Exception {
        ObjectNode b = settingsBody(); // activeEnvironment TEST, TEST credentials UNSET
        b.put("priceChangeNoticeDays", 14);
        assertThat(putSettings(b).getStatus()).isEqualTo(200);
    }

    @Test
    void theViewAndTheAuditNeverCarryAKey() throws Exception {
        putCredentials("test", TEST_PK, TEST_SK);
        verifyCredentials("test");
        ObjectNode b = settingsBody();
        b.put("billingEnabled", true);
        assertThat(putSettings(b).getStatus()).isEqualTo(200);
        String all = asAdmin(get(SETTINGS), null).getContentAsString() + asAdmin(get(SETTINGS + "/audit"), null).getContentAsString()
                + asAdmin(get(CREDS), null).getContentAsString();
        assertThat(all).doesNotContain(TEST_SK).doesNotContain(TEST_PK).doesNotContain("DOCSEXAMPLEONLYNOTAKEY");
        assertThat(all).contains("\"0002\"").contains("\"0001\"");
    }

    // ------------------------------------------------------------------------------------------ number preview

    @Test
    void numberPreviewUsesTheSuppliedFormatsAndTheSavedOnesForTheRest_noStateChange() throws Exception {
        long audit = settingsAuditRows();
        MockHttpServletResponse r = asAdmin(post(SETTINGS + "/number-preview").contentType("application/json"), "{\"invoiceNumberFormat\":\"INV/{YYYY}/{NNNNNN}\"}");
        assertThat(r.getStatus()).isEqualTo(200);
        JsonNode d = data(r);
        int year = java.time.Year.now(java.time.ZoneOffset.UTC).getValue();
        assertThat(d.get("INVOICE").asText()).isEqualTo("INV/" + year + "/000001");
        assertThat(d.get("RECEIPT").asText()).isEqualTo("REC-" + year + "-000001");
        assertThat(d.get("CREDIT_NOTE").asText()).isEqualTo("CN-" + year + "-000001");
        assertThat(scalar("SELECT version FROM master.billing_settings")).isZero();
        assertThat(settingsAuditRows()).isEqualTo(audit);
        assertThat(string("SELECT invoice_number_format FROM master.billing_settings")).isEqualTo("SUB-{YYYY}-{NNNNNN}");
    }

    @Test
    void numberPreviewUsesTheBillingClockForTheYear() throws Exception {
        billingClock.advanceSeconds(3L * 366 * 24 * 3600);
        int year = billingClock.now().atZone(java.time.ZoneOffset.UTC).getYear();
        JsonNode d = data(asAdmin(post(SETTINGS + "/number-preview").contentType("application/json"), "{\"receiptNumberFormat\":\"R-{YYYY}-{NNNNNN}\"}"));
        assertThat(d.get("RECEIPT").asText()).isEqualTo("R-" + year + "-000001");
        assertThat(d.get("INVOICE").asText()).isEqualTo("SUB-" + year + "-000001");
    }

    @Test
    void numberPreviewRefusesBadFormatsWithTheFieldMap() throws Exception {
        for (String bad : List.of("{\"invoiceNumberFormat\":\"INV-{YYYY}\"}", "{\"receiptNumberFormat\":\"REC %s {NNNNNN}\"}",
                "{\"creditNoteNumberFormat\":\"CN-$1-{NNNNNN}\"}", "{\"invoiceNumberFormat\":5}", "{\"invoiceNumberFormat\":null}", "{}", "{\"x\":1}")) {
            MockHttpServletResponse r = asAdmin(post(SETTINGS + "/number-preview").contentType("application/json"), bad);
            assertThat(r.getStatus()).as(bad).isEqualTo(400);
            assertThat(body(r).get("description").asText()).as(bad).isEqualTo("Validation failed");
        }
        assertThat(asAdmin(post(SETTINGS + "/number-preview").contentType("application/json"), "[]").getStatus()).isEqualTo(400);
    }

    @Test
    void aFormatWithMetaCharactersCannotBeSavedSoTheSubstitutionNeverSeesThem() throws Exception {
        for (String bad : List.of("A%sB-{NNNNNN}", "A$1B-{NNNNNN}", "A\\1B-{NNNNNN}", "A(B)-{NNNNNN}", "A{0}-{NNNNNN}")) {
            ObjectNode b = settingsBody();
            b.put("invoiceNumberFormat", bad);
            assertThat(putSettings(b).getStatus()).as(bad).isEqualTo(400);
        }
    }

    // ------------------------------------------------------------------------------------------ audit endpoint

    @Test
    void theAuditIsPagedNewestFirstWithBoundedSize() throws Exception {
        for (int g = 8; g <= 10; g++) {
            ObjectNode b = settingsBody();
            b.put("graceDays", g);
            assertThat(putSettings(b).getStatus()).isEqualTo(200);
        }
        JsonNode p = data(asAdmin(get(SETTINGS + "/audit?size=2&page=0"), null));
        assertThat(p.get("items")).hasSize(2);
        assertThat(p.get("items").get(0).get("settingsVersion").asInt()).isEqualTo(3);
        assertThat(p.get("items").get(1).get("settingsVersion").asInt()).isEqualTo(2);
        assertThat(p.get("size").asInt()).isEqualTo(2);
        assertThat(p.get("totalPages").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(data(asAdmin(get(SETTINGS + "/audit?size=500"), null)).get("size").asInt()).isEqualTo(100);
        assertThat(asAdmin(get(SETTINGS + "/audit?page=-1"), null).getStatus()).isEqualTo(400);
        assertThat(asAdmin(get(SETTINGS + "/audit?size=0"), null).getStatus()).isEqualTo(400);
        assertThat(asAdmin(get(SETTINGS + "/audit?page=abc"), null).getStatus()).isEqualTo(400);
    }

    // ------------------------------------------------------------------------------------------ throttle

    @Test
    void writesAreThrottledPerAdmin_429WithRetryAfter() throws Exception {
        ObjectNode b = settingsBody();
        int ok = 0;
        MockHttpServletResponse last = null;
        for (int i = 0; i < 40; i++) {
            last = putSettings(b);
            if (last.getStatus() == 429) {
                break;
            }
            ok++;
        }
        assertThat(ok).isEqualTo(30);
        assertThat(last.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(last.getHeader("Retry-After"))).isGreaterThanOrEqualTo(1);
        assertThat(body(last).get("description").asText()).isEqualTo("Too many requests. Please try again later.");
        // reads are never throttled by the write bucket
        assertThat(asAdmin(get(SETTINGS), null).getStatus()).isEqualTo(200);
        // another admin has their own bucket
        var other = admins.save(com.example.perfume_budget.platform.model.PlatformAdmin.builder().email("billing2-" + SEQ.incrementAndGet() + "@platform.test")
                .password(encoder.encode(PW)).fullName("Other").isActive(true).build());
        jakarta.servlet.http.Cookie c2 = loginCookie(other);
        assertThat(call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(SETTINGS).cookie(c2).header("Origin", CONSOLE)
                .contentType("application/json").content(b.toString())).getStatus()).isEqualTo(200);
    }
}
