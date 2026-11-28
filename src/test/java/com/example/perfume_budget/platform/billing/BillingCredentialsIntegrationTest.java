package com.example.perfume_budget.platform.billing;

import com.example.perfume_budget.platform.billing.credentials.BillingCredentialService;
import com.example.perfume_budget.platform.billing.credentials.BillingSecretCipher;
import com.example.perfume_budget.platform.billing.fake.FakeCard;
import com.example.perfume_budget.platform.billing.paystack.PaystackResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * BILL-01 AC3 / AC4 / AC8 and S2: the credentials API on PostgreSQL against the fake Paystack. WRITE-ONLY secrets (a log
 * capture and a grep of every response, audit row, stored column and exception for the key), encryption at rest with a key
 * version and AAD, verify success / failure / outage, version conflict, throttle, platform-fault health.
 */
class BillingCredentialsIntegrationTest extends AbstractBillingIntegrationTest {

    @Autowired BillingCredentialService credentialService;

    private JsonNode data(MockHttpServletResponse r) throws Exception {
        return body(r).get("data");
    }

    private static String stored(String column, String env) {
        return string("SELECT " + column + " FROM master.billing_paystack_credentials WHERE environment = ?", env);
    }

    // ------------------------------------------------------------------------------------------ put

    @Test
    void putStoresTheSecretEncryptedWithTheKeyVersionAndAad_setsUnverified_andReturnsOnlyMetadata() throws Exception {
        MockHttpServletResponse r = putCredentials("test", TEST_PK, TEST_SK);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        JsonNode d = data(r);
        assertThat(d.toString()).isEqualTo("{\"environment\":\"TEST\",\"configured\":true,\"status\":\"UNVERIFIED\",\"publicKeyLast4\":\"0001\","
                + "\"secretKeyLast4\":\"0002\",\"verifiedAt\":null,\"failureCode\":null,\"lastWebhookAt\":null,\"lastWebhookOkAt\":null,"
                + "\"webhookUrl\":\"https://api.shopfi.test/api/v1/platform/billing/webhooks/paystack/test\",\"version\":1,"
                + "\"updatedAt\":" + d.get("updatedAt") + ",\"updatedBy\":\"" + admin.getEmail() + "\"}");

        String cipherText = stored("secret_key_encrypted", "TEST");
        assertThat(cipherText).startsWith("v1:").doesNotContain(TEST_SK).doesNotContain("DOCSEXAMPLE");
        assertThat(scalar("SELECT secret_key_version FROM master.billing_paystack_credentials WHERE environment = 'TEST'")).isEqualTo(1);
        // decrypts with the AAD of THIS row and field only
        assertThat(cipher.decrypt(cipherText, BillingSecretCipher.aad("billing_paystack_credentials", "TEST", "secret_key"))).isEqualTo(TEST_SK);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> cipher.decrypt(cipherText,
                BillingSecretCipher.aad("billing_paystack_credentials", "LIVE", "secret_key"))).isInstanceOf(IllegalStateException.class);
        assertThat(stored("secret_last4", "TEST")).isEqualTo("0002");
        // the other environment is untouched
        assertThat(stored("status", "LIVE")).isEqualTo("UNSET");
    }

    @Test
    void theSecretNeverAppearsInAnyResponseAuditRowStoredColumnOrLog() throws Exception {
        try (LogCapture logs = new LogCapture()) {
            List<String> seen = new ArrayList<>();
            seen.add(putCredentials("test", TEST_PK, TEST_SK).getContentAsString());
            seen.add(verifyCredentials("test").getContentAsString());
            seen.add(asAdmin(get(CREDS), null).getContentAsString());
            seen.add(asAdmin(get(SETTINGS), null).getContentAsString());
            seen.add(asAdmin(get(SETTINGS + "/audit"), null).getContentAsString());
            // error paths echo nothing either
            seen.add(putCredentials("test", "not-a-key", TEST_SK).getContentAsString());
            seen.add(putCredentials("live", TEST_PK, TEST_SK).getContentAsString());
            seen.add(putCredentials("staging", TEST_PK, TEST_SK).getContentAsString());
            seen.add(asAdmin(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(CREDS + "/test").contentType("application/json"),
                    "{\"publicKey\":\"" + TEST_PK + "\",\"secretKey\":12345}").getContentAsString());
            fake.acceptSecret("sk_test_someOtherKeyThatIsAccepted0001"); // our key is now rejected: the failure paths log too
            seen.add(verifyCredentials("test").getContentAsString());
            String audit = string("SELECT string_agg(changes::text, ' ') FROM master.billing_settings_audit_log");
            String columns = string("SELECT string_agg(coalesce(public_key,'') || coalesce(secret_key_encrypted,'') || coalesce(secret_last4,''), ' ') "
                    + "FROM master.billing_paystack_credentials");
            for (String s : seen) {
                assertThat(s).doesNotContain(TEST_SK).doesNotContain("DOCSEXAMPLEONLYNOTAKEY0002");
                assertThat(s).doesNotContain(TEST_PK);
            }
            assertThat(audit).doesNotContain(TEST_SK).doesNotContain(TEST_PK).doesNotContain("DOCSEXAMPLEONLYNOTAKEY");
            assertThat(columns).as("not stored in clear").doesNotContain("DOCSEXAMPLEONLYNOTAKEY0002").doesNotContain(TEST_SK);
            assertThat(logs.text()).doesNotContain(TEST_SK).doesNotContain("DOCSEXAMPLEONLYNOTAKEY0002");
        }
    }

    @Test
    void theAuditShowsChangedUnsetAndLast4_neverTheKey() throws Exception {
        long before = maxAuditId("billing_settings_audit_log");
        putCredentials("test", TEST_PK, TEST_SK);
        JsonNode row = data(asAdmin(get(SETTINGS + "/audit"), null)).get("items").get(0);
        assertThat(row.get("id").asLong()).isGreaterThan(before);
        JsonNode changes = row.get("changes");
        assertThat(changes.toString()).contains("{\"field\":\"credentials.TEST.secretKey\",\"before\":\"unset\",\"after\":\"changed\"}")
                .contains("{\"field\":\"credentials.TEST.secretKeyLast4\",\"before\":null,\"after\":\"0002\"}");
        assertThat(changes).as("the contract's audit row of a credentials write: the secret and its last 4").hasSize(2);
        // a replacement says "set" -> "changed"
        putCredentials("test", TEST_PK, "sk_test_DOCSEXAMPLEONLYNOTAKEY0009");
        JsonNode again = data(asAdmin(get(SETTINGS + "/audit"), null)).get("items").get(0).get("changes");
        assertThat(again.toString()).contains("{\"field\":\"credentials.TEST.secretKey\",\"before\":\"set\",\"after\":\"changed\"}")
                .contains("\"before\":\"0002\",\"after\":\"0009\"");
    }

    private static long maxAuditId(String table) {
        return scalar("SELECT coalesce(max(id), 0) FROM master." + table);
    }

    @Test
    void keyFormatAndEnvironmentPrefixAreChecked_theMessagesNeverEchoTheValue() throws Exception {
        MockHttpServletResponse bad = putCredentials("test", "not-a-key", TEST_SK);
        assertThat(bad.getStatus()).isEqualTo(400);
        assertThat(body(bad).get("description").asText()).isEqualTo("Validation failed");
        assertThat(body(bad).get("data").fieldNames()).toIterable().containsExactly("publicKey");
        assertThat(bad.getContentAsString()).doesNotContain("not-a-key");

        // keys of the other environment: the field says which prefix is expected
        MockHttpServletResponse wrongEnv = putCredentials("test", TEST_PK, "sk_" + "live_" + "DOCSEXAMPLEONLYNOTAKEY0002");
        assertThat(wrongEnv.getStatus()).isEqualTo(400);
        assertThat(body(wrongEnv).toString()).isEqualTo("{\"description\":\"Validation failed\",\"data\":{\"secretKey\":\"must carry the key prefix of the test environment\"}}");
        MockHttpServletResponse liveWithTest = putCredentials("live", TEST_PK, TEST_SK);
        assertThat(body(liveWithTest).get("data").fieldNames()).toIterable().containsExactly("publicKey", "secretKey");

        for (String[] pair : new String[][]{{"pk_test_short", TEST_SK}, {TEST_PK, "sk_test_" + "x".repeat(129)}, {TEST_PK, "sk_test_has space 0000000000"},
                {TEST_PK, "pk_test_DOCSEXAMPLEONLYNOTAKEY0002"}, {"sk_test_DOCSEXAMPLEONLYNOTAKEY0001", TEST_SK}, {"", ""}}) {
            assertThat(putCredentials("test", pair[0], pair[1]).getStatus()).as(pair[0] + "/" + pair[1]).isEqualTo(400);
        }
        // boundaries: 16 and 128 characters after the prefix are accepted
        assertThat(putCredentials("test", "pk_test_" + "A".repeat(16), "sk_test_" + "B".repeat(16)).getStatus()).isEqualTo(200);
        assertThat(putCredentials("test", "pk_test_" + "A".repeat(128), "sk_test_" + "B".repeat(128)).getStatus()).isEqualTo(200);
        assertThat(putCredentials("test", "pk_test_" + "A".repeat(15), "sk_test_" + "B".repeat(16)).getStatus()).isEqualTo(400);
        assertThat(stored("status", "LIVE")).as("nothing was written for live").isEqualTo("UNSET");
    }

    @Test
    void anUnknownEnvironmentIs404_onlyLowerCaseTestAndLiveExist() throws Exception {
        for (String env : List.of("TEST", "Live", "staging", "prod", "tests")) {
            assertThat(putCredentials(env, TEST_PK, TEST_SK).getStatus()).as(env).isEqualTo(404);
            assertThat(verifyCredentials(env).getStatus()).as(env).isEqualTo(404);
        }
    }

    @Test
    void expectedVersionIsTheOptimisticLock_409WithBothVersions() throws Exception {
        putCredentials("test", TEST_PK, TEST_SK); // version 1
        MockHttpServletResponse stale = asAdmin(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(CREDS + "/test")
                .contentType("application/json"), "{\"publicKey\":\"" + TEST_PK + "\",\"secretKey\":\"sk_test_DOCSEXAMPLEONLYNOTAKEY0009\",\"expectedVersion\":0}");
        assertThat(stale.getStatus()).isEqualTo(409);
        assertThat(body(stale).get("description").asText()).isEqualTo("The credentials were changed by someone else. Reload and review.");
        assertThat(body(stale).get("data")).isEqualTo(JSON.readTree("{\"code\":\"CREDENTIALS_VERSION_CONFLICT\",\"expectedVersion\":0,\"currentVersion\":1}"));
        assertThat(stored("secret_last4", "TEST")).as("nothing written").isEqualTo("0002");
        MockHttpServletResponse okVersion = asAdmin(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(CREDS + "/test")
                .contentType("application/json"), "{\"publicKey\":\"" + TEST_PK + "\",\"secretKey\":\"sk_test_DOCSEXAMPLEONLYNOTAKEY0009\",\"expectedVersion\":1}");
        assertThat(okVersion.getStatus()).isEqualTo(200);
        assertThat(data(okVersion).get("version").asInt()).isEqualTo(2);
        // without expectedVersion the write is unconditional (key rotation after a leak must never wait)
        assertThat(putCredentials("test", TEST_PK, TEST_SK).getStatus()).isEqualTo(200);
        assertThat(asAdmin(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(CREDS + "/test").contentType("application/json"),
                "{\"publicKey\":\"" + TEST_PK + "\",\"secretKey\":\"" + TEST_SK + "\",\"expectedVersion\":\"3\"}").getStatus()).isEqualTo(400);
    }

    @Test
    void twoConcurrentWritersFromTheSameVersion_oneWins_theOtherIs409_neverA503() throws Exception {
        for (int round = 0; round < 4; round++) {
            resetBillingTables();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> fs = new ArrayList<>();
            for (String suffix : List.of("0007", "0008")) {
                fs.add(pool.submit(() -> {
                    go.await();
                    return asAdmin(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(CREDS + "/test").contentType("application/json"),
                            "{\"publicKey\":\"" + TEST_PK + "\",\"secretKey\":\"sk_test_DOCSEXAMPLEONLYNOTAKEY" + suffix + "\",\"expectedVersion\":0}").getStatus();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : fs) {
                statuses.add(f.get(30, TimeUnit.SECONDS));
            }
            pool.shutdownNow();
            assertThat(statuses).as("round " + round).containsExactlyInAnyOrder(200, 409);
            assertThat(scalar("SELECT version FROM master.billing_paystack_credentials WHERE environment = 'TEST'")).isEqualTo(1);
        }
    }

    @Test
    void getListsBothEnvironmentsTestFirst() throws Exception {
        putCredentials("live", "pk_" + "live_" + "DOCSEXAMPLEONLYNOTAKEY0001", "sk_" + "live_" + "DOCSEXAMPLEONLYNOTAKEY0002");
        JsonNode list = data(asAdmin(get(CREDS), null)).get("credentials");
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("environment").asText()).isEqualTo("TEST");
        assertThat(list.get(0).get("configured").asBoolean()).isFalse();
        assertThat(list.get(1).get("environment").asText()).isEqualTo("LIVE");
        assertThat(list.get(1).get("status").asText()).isEqualTo("UNVERIFIED");
        assertThat(list.get(1).get("webhookUrl").asText()).endsWith("/api/v1/platform/billing/webhooks/paystack/live");
    }

    // ------------------------------------------------------------------------------------------ verify

    @Test
    void verifyWithAnAcceptedKeyIsVerifiedWithTimestamp_andCallsPaystackWithTheStoredKey() throws Exception {
        putCredentials("test", TEST_PK, TEST_SK);
        MockHttpServletResponse r = verifyCredentials("test");
        assertThat(r.getStatus()).isEqualTo(200);
        JsonNode d = data(r);
        assertThat(d.get("status").asText()).isEqualTo("VERIFIED");
        assertThat(d.get("verifiedAt").asText()).endsWith("Z");
        assertThat(d.get("failureCode").isNull()).isTrue();
        assertThat(d.get("version").asInt()).as("verify does not touch the key-write version").isEqualTo(1);
        assertThat(fake.requests("GET", "/transaction")).hasSize(1);
        assertThat(fake.requests("GET", "/transaction").get(0).query()).isEqualTo("perPage=1&page=1");
        assertThat(fake.requests()).allMatch(x -> x.authorizedWithKnownKey());
        // audit: status change UNVERIFIED -> VERIFIED by this admin
        JsonNode row = data(asAdmin(get(SETTINGS + "/audit"), null)).get("items").get(0);
        assertThat(row.get("actorEmail").asText()).isEqualTo(admin.getEmail());
        assertThat(row.get("changes").toString()).contains("{\"field\":\"credentials.TEST.status\",\"before\":\"UNVERIFIED\",\"after\":\"VERIFIED\"}");
    }

    @Test
    void verifyWithARejectedKeyIsFailedKeyRejected_aReplacementResetsItToUnverified() throws Exception {
        fake.acceptSecret("sk_test_someOtherKeyThatIsAccepted0001");
        putCredentials("test", TEST_PK, TEST_SK);
        MockHttpServletResponse r = verifyCredentials("test");
        assertThat(r.getStatus()).as("a failed verification is a result, not a server error").isEqualTo(200);
        JsonNode d = data(r);
        assertThat(d.get("status").asText()).isEqualTo("FAILED");
        assertThat(d.get("failureCode").asText()).isEqualTo("KEY_REJECTED");
        assertThat(d.get("verifiedAt").isNull()).isTrue();
        // the loud platform-fault indicator while the ACTIVE environment is rejected
        JsonNode health = data(asAdmin(get(SETTINGS), null)).get("health");
        assertThat(health.get("paused").asBoolean()).isTrue();
        assertThat(health.get("reason").asText()).isEqualTo("KEY_REJECTED");
        assertThat(health.get("since").asText()).endsWith("Z");
        // the key is replaced and verifies: the indicator clears
        fake.reset();
        assertThat(putCredentials("test", TEST_PK, "sk_test_DOCSEXAMPLEONLYNOTAKEY0009").getStatus()).isEqualTo(200);
        assertThat(stored("status", "TEST")).isEqualTo("UNVERIFIED");
        assertThat(stored("failure_code", "TEST")).isNull();
        assertThat(data(verifyCredentials("test")).get("status").asText()).isEqualTo("VERIFIED");
        assertThat(data(asAdmin(get(SETTINGS), null)).get("health").get("paused").asBoolean()).isFalse();
    }

    @Test
    void verifyWithoutStoredKeysIs409CredentialsNotConfigured_andPaystackIsNeverCalled() throws Exception {
        MockHttpServletResponse r = verifyCredentials("live");
        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(body(r).toString()).isEqualTo("{\"description\":\"No keys are stored for this environment.\",\"data\":{\"code\":\"CREDENTIALS_NOT_CONFIGURED\"}}");
        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void anOutageOrAnOpenCircuitIs503ProviderUnavailableWithRetryAfter_andTheStatusIsUnchanged() throws Exception {
        putCredentials("test", TEST_PK, TEST_SK);
        assertThat(verifyCredentials("test").getStatus()).isEqualTo(200); // VERIFIED
        fake.outage(500);
        MockHttpServletResponse r = verifyCredentials("test");
        assertThat(r.getStatus()).isEqualTo(503);
        assertThat(r.getHeader("Retry-After")).isNotNull();
        assertThat(body(r).toString()).isEqualTo("{\"description\":\"The payment provider is not reachable right now. Try again shortly.\",\"data\":{\"code\":\"PROVIDER_UNAVAILABLE\"}}");
        assertThat(stored("status", "TEST")).as("a network failure never changes the status").isEqualTo("VERIFIED");
        for (int i = 0; i < 4; i++) { // five consecutive failures open the circuit
            assertThat(paystackClient.ping(BillingEnvironment.TEST)).isInstanceOf(PaystackResult.Unavailable.class);
        }
        // the circuit is now open: the next verify does not even reach the fake
        int reached = fake.requests().size();
        assertThat(verifyCredentials("test").getStatus()).isEqualTo(503);
        assertThat(fake.requests()).hasSize(reached);
        assertThat(stored("status", "TEST")).isEqualTo("VERIFIED");
        fake.outage(0);
    }

    @Test
    void verifyIsThrottledPerAdmin_5Per10Minutes() throws Exception {
        putCredentials("test", TEST_PK, TEST_SK);
        int ok = 0;
        MockHttpServletResponse last = null;
        for (int i = 0; i < 10; i++) {
            last = verifyCredentials("test");
            if (last.getStatus() == 429) {
                break;
            }
            ok++;
        }
        assertThat(ok).isEqualTo(5);
        assertThat(last.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(last.getHeader("Retry-After"))).isGreaterThanOrEqualTo(1);
        assertThat(fake.requests("GET", "/transaction")).as("a throttled request never reaches Paystack").hasSize(5);
    }

    @Test
    void aPaystack401OnAnyBillingCallMarksTheEnvironmentFailedAsAPlatformFault() throws Exception {
        putCredentials("test", TEST_PK, TEST_SK);
        verifyCredentials("test");
        fake.acceptSecret("sk_test_someOtherKeyThatIsAccepted0001"); // keys rotated at Paystack
        PaystackResult<?> r = paystackClient.verify(BillingEnvironment.TEST, "sub-t-1-tc-0-1");
        assertThat(r).isInstanceOf(PaystackResult.KeyRejected.class);
        assertThat(stored("status", "TEST")).isEqualTo("FAILED");
        assertThat(stored("failure_code", "TEST")).isEqualTo("KEY_REJECTED");
        JsonNode audit = data(asAdmin(get(SETTINGS + "/audit"), null)).get("items").get(0);
        assertThat(audit.get("actorEmail").asText()).isEqualTo("system");
        assertThat(audit.get("changes").toString()).contains("\"before\":\"VERIFIED\",\"after\":\"FAILED\"");
        // recorded once: a second rejection is not a second status change
        long rows = settingsAuditRows();
        paystackClient.verify(BillingEnvironment.TEST, "sub-t-1-tc-0-1");
        assertThat(settingsAuditRows()).isEqualTo(rows);
    }

    @Test
    void theClientReadsTheStoredKeyAtCallTime_aRotationIsEffectiveImmediately() throws Exception {
        putCredentials("test", TEST_PK, TEST_SK);
        fake.acceptSecret("sk_test_DOCSEXAMPLEONLYNOTAKEY0009");
        assertThat(paystackClient.ping(BillingEnvironment.TEST)).isInstanceOf(PaystackResult.KeyRejected.class);
        putCredentials("test", TEST_PK, "sk_test_DOCSEXAMPLEONLYNOTAKEY0009");
        assertThat(paystackClient.ping(BillingEnvironment.TEST)).isInstanceOf(PaystackResult.Ok.class);
        assertThat(credentialService.secretKey(BillingEnvironment.TEST)).contains("sk_test_DOCSEXAMPLEONLYNOTAKEY0009");
        assertThat(credentialService.secretKey(BillingEnvironment.LIVE)).isEmpty();
    }
}
