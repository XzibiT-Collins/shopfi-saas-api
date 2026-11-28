package com.example.perfume_budget.platform.billing.fake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * BILL-01 AC7: the fake behaves like the documented Paystack responses. The shapes are copied from the Paystack documentation
 * fixtures (initialize, verify, charge_authorization, refund, transaction list, customer); every scripted outcome, the hosted
 * checkout, the recorder and the webhook emitter (real HMAC-SHA512, duplicates, delay, reorder, drop, bad signature) is
 * exercised over HTTP, exactly as the application and the frontend e2e use it.
 */
class FakePaystackTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String KEY = "sk_test_DOCSEXAMPLEONLYNOTAKEY0002";
    FakePaystackServer fake;
    HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeEach
    void start() {
        fake = FakePaystackServer.start();
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    record Reply(int status, JsonNode json, String raw, String location) { }

    Reply call(String method, String path, String body, String key) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(fake.baseUrl() + path));
        if (key != null) {
            b.header("Authorization", "Bearer " + key);
        }
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        HttpResponse<String> r = http.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        JsonNode json = null;
        try {
            json = JSON.readTree(r.body());
        } catch (Exception ignored) {
            // html
        }
        return new Reply(r.statusCode(), json, r.body(), r.headers().firstValue("Location").orElse(null));
    }

    Reply call(String method, String path, String body) throws Exception {
        return call(method, path, body, KEY);
    }

    String initialize(String ref, long amount) throws Exception {
        Reply r = call("POST", "/transaction/initialize",
                "{\"email\":\"owner@acme.example\",\"amount\":" + amount + ",\"currency\":\"GHS\",\"reference\":\"" + ref
                        + "\",\"callback_url\":\"https://api.shopfi.test/cb\",\"channels\":[\"card\"],\"metadata\":{\"tenantId\":\"12\"}}");
        assertThat(r.status()).isEqualTo(200);
        return r.json().get("data").get("access_code").asText();
    }

    String pay(String accessCode, FakeCard card) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(fake.baseUrl() + "/checkout/" + accessCode + "/pay"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("card=" + card.name())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(302);
        return r.headers().firstValue("Location").orElseThrow();
    }

    String authCode(String ref) throws Exception {
        return call("GET", "/transaction/verify/" + ref, null).json.at("/data/authorization/authorization_code").asText();
    }

    // ------------------------------------------------------------------------------------------ shapes

    @Test
    void initializeAnswersThePaystackShape() throws Exception {
        Reply r = call("POST", "/transaction/initialize",
                "{\"email\":\"owner@acme.example\",\"amount\":10000,\"reference\":\"sub-card-t-shape\",\"callback_url\":\"https://api.shopfi.test/cb\"}");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("status").asBoolean()).isTrue();
        assertThat(r.json().get("message").asText()).isEqualTo("Authorization URL created");
        JsonNode d = r.json().get("data");
        assertThat(d.fieldNames()).toIterable().containsExactlyInAnyOrder("authorization_url", "access_code", "reference");
        assertThat(d.get("authorization_url").asText()).isEqualTo(fake.checkoutPrefix() + d.get("access_code").asText());
        assertThat(d.get("reference").asText()).isEqualTo("sub-card-t-shape");
    }

    @Test
    void initializeRefusesADuplicateReferenceAndMissingFields() throws Exception {
        initialize("sub-card-t-dup", 100);
        Reply dup = call("POST", "/transaction/initialize", "{\"email\":\"a@b.c\",\"amount\":100,\"reference\":\"sub-card-t-dup\"}");
        assertThat(dup.status()).isEqualTo(400);
        assertThat(dup.json().get("status").asBoolean()).isFalse();
        assertThat(dup.json().get("message").asText()).isEqualTo("Duplicate Transaction Reference");
        assertThat(call("POST", "/transaction/initialize", "{\"amount\":100}").status()).isEqualTo(400);
    }

    @Test
    void verifyAnswersTheDocumentedFieldsForASuccessfulTransaction() throws Exception {
        String access = initialize("sub-t-12-tc-0-1", 14900);
        String location = pay(access, FakeCard.SUCCESS);
        assertThat(location).isEqualTo("https://api.shopfi.test/cb?trxref=sub-t-12-tc-0-1&reference=sub-t-12-tc-0-1");
        Reply r = call("GET", "/transaction/verify/sub-t-12-tc-0-1", null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("message").asText()).isEqualTo("Verification successful");
        JsonNode d = r.json().get("data");
        assertThat(d.fieldNames()).toIterable().contains("id", "domain", "status", "reference", "amount", "gateway_response", "paid_at",
                "created_at", "channel", "currency", "ip_address", "metadata", "fees", "authorization", "customer", "requested_amount");
        assertThat(d.get("status").asText()).isEqualTo("success");
        assertThat(d.get("amount").asLong()).isEqualTo(14900);
        assertThat(d.get("currency").asText()).isEqualTo("GHS");
        assertThat(d.get("gateway_response").asText()).isEqualTo("Successful");
        assertThat(d.get("metadata").get("tenantId").asText()).isEqualTo("12");
        JsonNode a = d.get("authorization");
        assertThat(a.fieldNames()).toIterable().contains("authorization_code", "bin", "last4", "exp_month", "exp_year", "channel",
                "card_type", "bank", "country_code", "brand", "reusable", "signature");
        assertThat(a.get("authorization_code").asText()).startsWith("AUTH_");
        assertThat(a.get("reusable").asBoolean()).isTrue();
        assertThat(d.get("customer").get("email").asText()).isEqualTo("owner@acme.example");
        assertThat(d.get("customer").get("customer_code").asText()).startsWith("CUS_");
    }

    @Test
    void verifyOfAnUnknownReferenceIs404WithStatusFalse() throws Exception {
        Reply r = call("GET", "/transaction/verify/nope", null);
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.json().get("status").asBoolean()).isFalse();
        assertThat(r.json().get("message").asText()).isEqualTo("Transaction reference not found");
    }

    @Test
    void anUnpaidCheckoutVerifiesAsNotSuccessful() throws Exception {
        initialize("sub-t-12-tc-0-2", 100);
        assertThat(call("GET", "/transaction/verify/sub-t-12-tc-0-2", null).json.at("/data/status").asText()).isEqualTo("abandoned");
    }

    // ------------------------------------------------------------------------------------------ hosted checkout

    @Test
    void theHostedCheckoutPageListsTheMagicCardsAndAPostCompletesOrDeclinesThenRedirects() throws Exception {
        String access = initialize("sub-card-t-page", 100);
        HttpResponse<String> page = http.send(HttpRequest.newBuilder(URI.create(fake.checkoutPrefix() + access)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
        for (FakeCard c : FakeCard.values()) {
            assertThat(page.body()).contains("value=\"" + c.name() + "\"");
        }
        assertThat(pay(access, FakeCard.DECLINED)).contains("reference=sub-card-t-page");
        assertThat(call("GET", "/transaction/verify/sub-card-t-page", null).json.at("/data/status").asText()).isEqualTo("failed");
        // an unknown checkout is a 404 page
        HttpResponse<String> unknown = http.send(HttpRequest.newBuilder(URI.create(fake.checkoutPrefix() + "unknown")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(unknown.statusCode()).isEqualTo(404);
    }

    @Test
    void aNonReusableCardYieldsReusableFalseAndCannotBeCharged() throws Exception {
        pay(initialize("sub-card-t-nr", 100), FakeCard.NON_REUSABLE);
        JsonNode auth = call("GET", "/transaction/verify/sub-card-t-nr", null).json.at("/data/authorization");
        assertThat(auth.get("reusable").asBoolean()).isFalse();
        Reply charge = call("POST", "/transaction/charge_authorization", "{\"email\":\"owner@acme.example\",\"amount\":100,\"reference\":\"sub-t-1-rn-1-1\",\"authorization_code\":\""
                + auth.get("authorization_code").asText() + "\"}");
        assertThat(charge.status()).isEqualTo(400);
        assertThat(fake.chargesCreated("sub-t-1-rn-1-1")).isZero();
    }

    // ------------------------------------------------------------------------------------------ charge scenarios

    String chargeBody(String ref, String code, long amount) {
        return "{\"email\":\"owner@acme.example\",\"amount\":" + amount + ",\"currency\":\"GHS\",\"reference\":\"" + ref + "\",\"authorization_code\":\"" + code + "\"}";
    }

    @Test
    void chargeAuthorizationAnswersTheDocumentedShape_andTheSameReferenceTwiceIsOneCharge() throws Exception {
        String code = fake.mintAuthorization(FakeCard.SUCCESS, "owner@acme.example");
        Reply first = call("POST", "/transaction/charge_authorization", chargeBody("sub-t-12-rn-1-1", code, 14900));
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.json().get("message").asText()).isEqualTo("Charge attempted");
        assertThat(first.json().at("/data/status").asText()).isEqualTo("success");
        assertThat(first.json().at("/data/amount").asLong()).isEqualTo(14900);
        assertThat(first.json().at("/data/authorization/authorization_code").asText()).isEqualTo(code);
        Reply second = call("POST", "/transaction/charge_authorization", chargeBody("sub-t-12-rn-1-1", code, 14900));
        assertThat(second.status()).isEqualTo(400);
        assertThat(second.json().get("message").asText()).isEqualTo("Duplicate Transaction Reference");
        assertThat(fake.chargesCreated("sub-t-12-rn-1-1")).isEqualTo(1);
        assertThat(fake.requests("POST", "/transaction/charge_authorization")).hasSize(2);
    }

    @Test
    void concurrentChargesOfOneReferenceCreateExactlyOne() throws Exception {
        String code = fake.mintAuthorization(FakeCard.SUCCESS, "owner@acme.example");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        List<java.util.concurrent.Future<Reply>> fs = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            fs.add(pool.submit(() -> call("POST", "/transaction/charge_authorization", chargeBody("sub-t-12-rn-5-1", code, 100))));
        }
        int ok = 0;
        for (var f : fs) {
            if (f.get().status() == 200) {
                ok++;
            }
        }
        pool.shutdownNow();
        assertThat(ok).isEqualTo(1);
        assertThat(fake.chargesCreated("sub-t-12-rn-5-1")).isEqualTo(1);
    }

    @Test
    void insufficientFundsAndDeclinedAreFailedTransactionsWithTheirGatewayResponses() throws Exception {
        String funds = fake.mintAuthorization(FakeCard.INSUFFICIENT_FUNDS, "o@a.c");
        String declined = fake.mintAuthorization(FakeCard.DECLINED, "o@a.c");
        Reply a = call("POST", "/transaction/charge_authorization", chargeBody("sub-t-1-rn-1-1", funds, 100));
        Reply b = call("POST", "/transaction/charge_authorization", chargeBody("sub-t-1-rn-1-2", declined, 100));
        assertThat(a.json().at("/data/status").asText()).isEqualTo("failed");
        assertThat(a.json().at("/data/gateway_response").asText()).isEqualTo("Insufficient Funds");
        assertThat(b.json().at("/data/status").asText()).isEqualTo("failed");
        assertThat(b.json().at("/data/gateway_response").asText()).isEqualTo("Declined");
    }

    @Test
    void serverErrorCardsAnswer500And502AndProcessNothing() throws Exception {
        String code = fake.mintAuthorization(FakeCard.SERVER_ERROR, "o@a.c");
        assertThat(call("POST", "/transaction/charge_authorization", chargeBody("sub-t-1-rn-1-1", code, 100)).status()).isEqualTo(500);
        fake.script("sub-t-1-rn-2", FakeOutcome.BAD_GATEWAY);
        assertThat(call("POST", "/transaction/charge_authorization", chargeBody("sub-t-1-rn-2-1", code, 100)).status()).isEqualTo(502);
        assertThat(fake.totalChargesCreated()).isZero();
        assertThat(call("GET", "/transaction/verify/sub-t-1-rn-1-1", null).status()).isEqualTo(404);
    }

    @Test
    void aTimeoutCardProcessesTheChargeThenHangs() throws Exception {
        fake.hangMillis(700);
        String code = fake.mintAuthorization(FakeCard.TIMEOUT, "o@a.c");
        HttpClient shortTimeout = HttpClient.newHttpClient();
        var req = HttpRequest.newBuilder(URI.create(fake.baseUrl() + "/transaction/charge_authorization")).timeout(java.time.Duration.ofMillis(200))
                .header("Authorization", "Bearer " + KEY).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(chargeBody("sub-t-1-rn-8-1", code, 100))).build();
        try {
            shortTimeout.send(req, HttpResponse.BodyHandlers.ofString());
            throw new AssertionError("expected a timeout");
        } catch (java.net.http.HttpTimeoutException expected) {
            // the client gave up
        }
        assertThat(fake.chargesCreated("sub-t-1-rn-8-1")).isEqualTo(1);
        assertThat(call("GET", "/transaction/verify/sub-t-1-rn-8-1", null).json.at("/data/status").asText()).isEqualTo("success");
    }

    @Test
    void wrongAmountAndWrongCurrencyCardsChargeButVerifyReportsOtherValues() throws Exception {
        String amount = fake.mintAuthorization(FakeCard.WRONG_AMOUNT, "o@a.c");
        String currency = fake.mintAuthorization(FakeCard.WRONG_CURRENCY, "o@a.c");
        call("POST", "/transaction/charge_authorization", chargeBody("sub-t-1-rn-1-1", amount, 10000));
        call("POST", "/transaction/charge_authorization", chargeBody("sub-t-1-rn-1-2", currency, 10000));
        assertThat(call("GET", "/transaction/verify/sub-t-1-rn-1-1", null).json.at("/data/amount").asLong()).isEqualTo(10001);
        assertThat(call("GET", "/transaction/verify/sub-t-1-rn-1-2", null).json.at("/data/currency").asText()).isEqualTo("USD");
        assertThat(call("GET", "/transaction/verify/sub-t-1-rn-1-2", null).json.at("/data/amount").asLong()).isEqualTo(10000);
    }

    @Test
    void aDelayedCardIsPendingThenSuccessWithAWebhook() throws Exception {
        WebhookSink sink = new WebhookSink();
        fake.delayedSeconds(1).webhookTarget(sink.url(), "whsec_fake_signing_key");
        String code = fake.mintAuthorization(FakeCard.DELAYED, "o@a.c");
        Reply r = call("POST", "/transaction/charge_authorization", chargeBody("sub-t-1-rn-4-1", code, 100));
        assertThat(r.json().at("/data/status").asText()).isEqualTo("pending");
        assertThat(call("GET", "/transaction/verify/sub-t-1-rn-4-1", null).json.at("/data/status").asText()).isEqualTo("pending");
        await().atMost(5, TimeUnit.SECONDS).until(() -> sink.bodies.size() == 1);
        assertThat(JSON.readTree(sink.bodies.get(0)).get("event").asText()).isEqualTo("charge.success");
        assertThat(call("GET", "/transaction/verify/sub-t-1-rn-4-1", null).json.at("/data/status").asText()).isEqualTo("success");
        sink.close();
    }

    @Test
    void aScriptedOutcomeOverridesTheCardForTheNextChargesOfAPrefixOnly() throws Exception {
        fake.script("sub-t-9-", FakeOutcome.DECLINED);
        String code = fake.mintAuthorization(FakeCard.SUCCESS, "o@a.c");
        assertThat(call("POST", "/transaction/charge_authorization", chargeBody("sub-t-9-rn-1-1", code, 100)).json.at("/data/status").asText()).isEqualTo("failed");
        assertThat(call("POST", "/transaction/charge_authorization", chargeBody("sub-t-9-rn-1-2", code, 100)).json.at("/data/status").asText()).isEqualTo("success");
        assertThat(call("POST", "/transaction/charge_authorization", chargeBody("sub-t-8-rn-1-1", code, 100)).json.at("/data/status").asText()).isEqualTo("success");
    }

    // ------------------------------------------------------------------------------------------ refund, list, customer

    @Test
    void refundAnswersTheDocumentedShapeAndBoundsTheAmount() throws Exception {
        String code = fake.mintAuthorization(FakeCard.SUCCESS, "o@a.c");
        call("POST", "/transaction/charge_authorization", chargeBody("sub-t-1-rn-1-1", code, 10000));
        Reply r = call("POST", "/refund", "{\"transaction\":\"sub-t-1-rn-1-1\",\"amount\":4000,\"merchant_note\":\"rf-1\"}");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("message").asText()).isEqualTo("Refund has been queued for processing");
        JsonNode d = r.json().get("data");
        assertThat(d.fieldNames()).toIterable().contains("transaction", "id", "amount", "currency", "status", "merchant_note", "expected_at");
        assertThat(d.get("status").asText()).isEqualTo("pending");
        assertThat(d.get("amount").asLong()).isEqualTo(4000);
        assertThat(d.get("transaction").get("reference").asText()).isEqualTo("sub-t-1-rn-1-1");
        assertThat(d.get("merchant_note").asText()).isEqualTo("rf-1");
        assertThat(call("POST", "/refund", "{\"transaction\":\"sub-t-1-rn-1-1\",\"amount\":6001}").status()).isEqualTo(400);
        assertThat(call("POST", "/refund", "{\"transaction\":\"sub-t-1-rn-1-1\"}").status()).isEqualTo(200); // the rest
        assertThat(call("POST", "/refund", "{\"transaction\":\"unknown\"}").status()).isEqualTo(400);
    }

    @Test
    void transactionListPagesNewestFirstWithMeta() throws Exception {
        String code = fake.mintAuthorization(FakeCard.SUCCESS, "o@a.c");
        for (int i = 1; i <= 5; i++) {
            call("POST", "/transaction/charge_authorization", chargeBody("sub-t-1-rn-" + i + "-1", code, 100 * i));
        }
        Reply page1 = call("GET", "/transaction?perPage=2&page=1", null);
        assertThat(page1.json.get("message").asText()).isEqualTo("Transactions retrieved");
        assertThat(page1.json.get("data")).hasSize(2);
        assertThat(page1.json.at("/data/0/reference").asText()).isEqualTo("sub-t-1-rn-5-1");
        assertThat(page1.json.at("/meta/total").asInt()).isEqualTo(5);
        assertThat(page1.json.at("/meta/pageCount").asInt()).isEqualTo(3);
        assertThat(call("GET", "/transaction?perPage=2&page=3", null).json.get("data")).hasSize(1);
        assertThat(call("GET", "/transaction?perPage=1&status=failed", null).json.get("data")).isEmpty();
        assertThat(call("GET", "/transaction?perPage=1", null).status()).isEqualTo(200);
    }

    @Test
    void customerAndDeactivateAuthorization() throws Exception {
        Reply c = call("POST", "/customer", "{\"email\":\"owner@acme.example\"}");
        assertThat(c.json.get("message").asText()).isEqualTo("Customer created");
        String code = c.json.at("/data/customer_code").asText();
        assertThat(call("GET", "/customer/" + code, null).json.at("/data/email").asText()).isEqualTo("owner@acme.example");
        assertThat(call("GET", "/customer/CUS_nope", null).status()).isEqualTo(404);
        String auth = fake.mintAuthorization(FakeCard.SUCCESS, "owner@acme.example");
        assertThat(call("POST", "/customer/deactivate_authorization", "{\"authorization_code\":\"" + auth + "\"}").status()).isEqualTo(200);
        assertThat(call("POST", "/transaction/charge_authorization", chargeBody("sub-t-1-rn-1-1", auth, 100)).status()).isEqualTo(400);
    }

    // ------------------------------------------------------------------------------------------ auth and recorder

    @Test
    void anUnknownOrMissingKeyIs401_andTheRecorderSeesEveryRequest() throws Exception {
        assertThat(call("GET", "/transaction?perPage=1", null, null).status()).isEqualTo(401);
        assertThat(call("GET", "/transaction?perPage=1", null, "not-a-key").status()).isEqualTo(401);
        fake.acceptSecret("sk_test_onlyThisOneIsAccepted0000001");
        assertThat(call("GET", "/transaction?perPage=1", null, KEY).status()).isEqualTo(401);
        assertThat(call("GET", "/transaction?perPage=1", null, "sk_test_onlyThisOneIsAccepted0000001").status()).isEqualTo(200);
        List<RecordedRequest> rec = fake.requests();
        assertThat(rec).hasSize(4);
        assertThat(rec).extracting(RecordedRequest::authorizedWithKnownKey).containsExactly(false, false, false, true);
        assertThat(rec.get(0).method()).isEqualTo("GET");
        assertThat(rec.get(0).path()).isEqualTo("/transaction");
    }

    @Test
    void resetClearsEverything() throws Exception {
        initialize("sub-card-t-reset", 100);
        fake.reset();
        assertThat(fake.requests()).isEmpty();
        assertThat(call("GET", "/transaction/verify/sub-card-t-reset", null).status()).isEqualTo(404);
    }

    @Test
    void theControlApiScriptsAndReportsOverHttp() throws Exception {
        assertThat(call("POST", "/__control/script", "{\"prefix\":\"sub-t-3-\",\"outcomes\":[\"DECLINED\"]}", null).status()).isEqualTo(200);
        String code = fake.mintAuthorization(FakeCard.SUCCESS, "o@a.c");
        assertThat(call("POST", "/transaction/charge_authorization", chargeBody("sub-t-3-rn-1-1", code, 100)).json.at("/data/status").asText()).isEqualTo("failed");
        Reply req = call("GET", "/__control/requests", null, null);
        assertThat(req.json.get("data")).isNotEmpty();
        assertThat(call("POST", "/__control/reset", "{}", null).status()).isEqualTo(200);
        assertThat(fake.requests()).isEmpty();
    }

    // ------------------------------------------------------------------------------------------ webhooks

    /** A tiny receiver collecting exactly what the fake sends (raw body and signature header). */
    static final class WebhookSink implements AutoCloseable {
        final List<String> bodies = new CopyOnWriteArrayList<>();
        final List<String> signatures = new CopyOnWriteArrayList<>();
        final HttpServer server;

        WebhookSink() {
            try {
                server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                server.createContext("/", ex -> {
                    bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    signatures.add(String.valueOf(ex.getRequestHeaders().getFirst("x-paystack-signature")));
                    ex.sendResponseHeaders(200, -1);
                    ex.close();
                });
                server.start();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/platform/billing/webhooks/paystack/test";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private String settledReference(String ref) throws Exception {
        pay(initialize(ref, 14900), FakeCard.SUCCESS);
        return ref;
    }

    @Test
    void webhooksCarryARealHmacSha512OfTheExactBody() throws Exception {
        try (WebhookSink sink = new WebhookSink()) {
            fake.webhookTarget(sink.url(), "whsec_fake_signing_key");
            settledReference("sub-t-12-tc-0-1");
            String body = fake.fireWebhook("charge.success", "sub-t-12-tc-0-1", WebhookOptions.once());
            assertThat(sink.bodies).containsExactly(body);
            JsonNode env = JSON.readTree(sink.bodies.get(0));
            assertThat(env.get("event").asText()).isEqualTo("charge.success");
            assertThat(env.at("/data/reference").asText()).isEqualTo("sub-t-12-tc-0-1");
            // independent HMAC (javax.crypto directly, not the fake's helper)
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA512");
            mac.init(new javax.crypto.spec.SecretKeySpec("whsec_fake_signing_key".getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            assertThat(sink.signatures.get(0)).isEqualTo(java.util.HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8))));
            assertThat(sink.signatures.get(0)).hasSize(128);
            assertThat(fake.sentWebhookBodies()).containsExactly(body);
        }
    }

    @Test
    void duplicatesBadSignatureMissingSignatureAndDropAreControllable() throws Exception {
        try (WebhookSink sink = new WebhookSink()) {
            fake.webhookTarget(sink.url(), "whsec_fake_signing_key");
            settledReference("sub-t-12-tc-0-1");
            fake.fireWebhook("charge.success", "sub-t-12-tc-0-1", WebhookOptions.once().copies(3));
            assertThat(sink.bodies).hasSize(3).containsOnly(sink.bodies.get(0));
            assertThat(sink.signatures).containsOnly(sink.signatures.get(0));
            sink.bodies.clear();
            sink.signatures.clear();
            String body = fake.fireWebhook("charge.success", "sub-t-12-tc-0-1", WebhookOptions.once().signature(WebhookOptions.Signature.BAD));
            String good = FakePaystackServer.hmacSha512Hex("whsec_fake_signing_key", body.getBytes(StandardCharsets.UTF_8));
            assertThat(sink.signatures.get(0)).hasSize(128).isNotEqualTo(good);
            fake.fireWebhook("charge.success", "sub-t-12-tc-0-1", WebhookOptions.once().signature(WebhookOptions.Signature.MISSING));
            assertThat(sink.signatures.get(1)).isEqualTo("null");
            int before = sink.bodies.size();
            fake.fireWebhook("charge.success", "sub-t-12-tc-0-1", WebhookOptions.once().dropped());
            assertThat(sink.bodies).hasSize(before);
        }
    }

    @Test
    void delayedAndReorderedWebhooks() throws Exception {
        try (WebhookSink sink = new WebhookSink()) {
            fake.webhookTarget(sink.url(), "whsec_fake_signing_key");
            settledReference("sub-t-12-tc-0-1");
            fake.fireWebhook("charge.success", "sub-t-12-tc-0-1", WebhookOptions.once().delay(400));
            assertThat(sink.bodies).isEmpty();
            await().atMost(5, TimeUnit.SECONDS).until(() -> sink.bodies.size() == 1);
            sink.bodies.clear();
            fake.fireReordered(List.of("charge.success", "refund.processed"), "sub-t-12-tc-0-1");
            assertThat(sink.bodies).hasSize(2);
            assertThat(JSON.readTree(sink.bodies.get(0)).get("event").asText()).isEqualTo("refund.processed");
            assertThat(JSON.readTree(sink.bodies.get(1)).get("event").asText()).isEqualTo("charge.success");
        }
    }

    @Test
    void rawBodiesCanBeSignedForReceiverTests() throws Exception {
        try (WebhookSink sink = new WebhookSink()) {
            fake.webhookTarget(sink.url(), "whsec_fake_signing_key");
            String exact = "{\"event\":\"charge.success\",\"data\":{\"reference\":\"x\"}}";
            fake.fireRaw(exact, WebhookOptions.once());
            assertThat(sink.bodies).containsExactly(exact);
            assertThat(sink.signatures.get(0)).isEqualTo(FakePaystackServer.hmacSha512Hex("whsec_fake_signing_key", exact.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
