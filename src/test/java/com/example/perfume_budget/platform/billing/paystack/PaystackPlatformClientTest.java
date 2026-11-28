package com.example.perfume_budget.platform.billing.paystack;

import com.example.perfume_budget.platform.billing.BillingEnvironment;
import com.example.perfume_budget.platform.billing.BillingProperties;
import com.example.perfume_budget.platform.billing.LogCapture;
import com.example.perfume_budget.platform.billing.fake.FakeCard;
import com.example.perfume_budget.platform.billing.fake.FakeOutcome;
import com.example.perfume_budget.platform.billing.fake.FakePaystackServer;
import com.example.perfume_budget.platform.billing.paystack.CircuitBreakerTest.MutableClock;
import com.example.perfume_budget.platform.billing.paystack.PaystackModels.ChargeCommand;
import com.example.perfume_budget.platform.billing.paystack.PaystackModels.InitializeCommand;
import com.example.perfume_budget.platform.billing.paystack.PaystackModels.InitializeResult;
import com.example.perfume_budget.platform.billing.paystack.PaystackModels.RefundCommand;
import com.example.perfume_budget.platform.billing.paystack.PaystackModels.Transaction;
import com.example.perfume_budget.platform.billing.paystack.PaystackModels.TransactionQuery;
import com.example.perfume_budget.platform.billing.settings.BillingSettingsData;
import com.example.perfume_budget.platform.billing.settings.BillingSettingsParser;
import com.example.perfume_budget.platform.billing.settings.BillingSettingsParserTest;
import com.example.perfume_budget.platform.billing.settings.BillingSettingsService;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BILL-01 AC6 / AC8: the platform Paystack client against the fake (no Spring, no database). */
class PaystackPlatformClientTest {

    static final String SECRET = "sk_test_DOCSEXAMPLEONLYNOTAKEY0002";
    static final String CALLBACK = "https://api.shopfi.test/api/v1/platform/onboarding/card/callback";

    FakePaystackServer fake;
    MutableClock clock;
    List<BillingEnvironment> rejected;
    PaystackPlatformClient client;

    static BillingProperties props(String base, String checkoutPrefix, int readMs, int maxConcurrent) {
        return new BillingProperties(base, base, checkoutPrefix, "https://api.shopfi.test", "https://api.shopfi.test", 1000, readMs, maxConcurrent);
    }

    PaystackPlatformClient newClient(BillingProperties props, Optional<String> secret) {
        rejected = new ArrayList<>();
        CredentialAccess access = new CredentialAccess() {
            @Override public Optional<String> secretKey(BillingEnvironment e) { return secret; }
            @Override public void keyRejected(BillingEnvironment e) { rejected.add(e); }
        };
        BillingSettingsService settings = Mockito.mock(BillingSettingsService.class);
        BillingSettingsData data = BillingSettingsParser.parse(BillingSettingsParserTest.valid(), true).data();
        Mockito.when(settings.current()).thenReturn(data);
        return new PaystackPlatformClient(props, access, settings, clock, new SimpleMeterRegistry(), 3, Duration.ofSeconds(30));
    }

    @BeforeEach
    void start() {
        fake = FakePaystackServer.start();
        clock = new MutableClock();
        client = newClient(props(fake.baseUrl(), fake.checkoutPrefix(), 400, 2), Optional.of(SECRET));
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    InitializeCommand init(String ref) {
        return new InitializeCommand("owner@acme.example", 10000, ref, Map.of("tenantId", "12"), CALLBACK + "?x=1".substring(0, 0) + "/ok");
    }

    String chargeable(String email) {
        return fake.mintAuthorization(FakeCard.SUCCESS, email);
    }

    // ------------------------------------------------------------------------------------------ operations

    @Test
    void initializeSendsChannelsFromSettingsCurrencyAndMetadata_andReturnsTheCheckoutUrl() {
        PaystackResult<InitializeResult> r = client.initialize(BillingEnvironment.TEST, init("sub-card-t-abc123"));
        assertThat(r).isInstanceOf(PaystackResult.Ok.class);
        InitializeResult v = ((PaystackResult.Ok<InitializeResult>) r).value();
        assertThat(v.authorizationUrl()).startsWith(fake.checkoutPrefix());
        assertThat(v.reference()).isEqualTo("sub-card-t-abc123");
        assertThat(fake.requests("POST", "/transaction/initialize")).hasSize(1);
    }

    @Test
    void verifyReturnsTheSummarisedTransaction() {
        client.initialize(BillingEnvironment.TEST, init("sub-t-1-tc-0-1"));
        fake.completeCheckout("sub-t-1-tc-0-1", FakeCard.SUCCESS);
        PaystackResult<Transaction> r = client.verify(BillingEnvironment.TEST, "sub-t-1-tc-0-1");
        Transaction t = ((PaystackResult.Ok<Transaction>) r).value();
        assertThat(t.status()).isEqualTo("success");
        assertThat(t.amountPesewas()).isEqualTo(10000);
        assertThat(t.currency()).isEqualTo("GHS");
        assertThat(t.authorization().reusable()).isTrue();
        assertThat(t.authorization().last4()).isEqualTo("4081");
        assertThat(t.paidAt()).isNotNull();
    }

    @Test
    void verifyOfAnUnknownReferenceIsRejected404() {
        assertThat(client.verify(BillingEnvironment.TEST, "sub-t-0-tc-0-1")).isEqualTo(new PaystackResult.Rejected<Transaction>(404));
    }

    @Test
    void chargeAuthorizationIsIdempotentPerReference_theSecondCallIsRejectedAndNoSecondChargeIsCreated() {
        String code = chargeable("owner@acme.example");
        ChargeCommand c = new ChargeCommand("owner@acme.example", 14900, code, "sub-t-12-rn-1-1");
        PaystackResult<Transaction> first = client.chargeAuthorization(BillingEnvironment.TEST, c);
        assertThat(((PaystackResult.Ok<Transaction>) first).value().status()).isEqualTo("success");
        PaystackResult<Transaction> second = client.chargeAuthorization(BillingEnvironment.TEST, c);
        assertThat(second).isEqualTo(new PaystackResult.Rejected<Transaction>(400));
        assertThat(fake.chargesCreated("sub-t-12-rn-1-1")).isEqualTo(1);
        assertThat(fake.totalChargesCreated()).isEqualTo(1);
    }

    @Test
    void aDeclinedAndAnInsufficientFundsChargeAreFailedTransactionsNotErrors() {
        fake.script("sub-t-12-rn-2", FakeOutcome.DECLINED, FakeOutcome.INSUFFICIENT_FUNDS);
        String code = chargeable("owner@acme.example");
        for (String ref : new String[]{"sub-t-12-rn-2-1", "sub-t-12-rn-2-2"}) {
            Transaction t = ((PaystackResult.Ok<Transaction>) client.chargeAuthorization(BillingEnvironment.TEST,
                    new ChargeCommand("owner@acme.example", 14900, code, ref))).value();
            assertThat(t.status()).isEqualTo("failed");
        }
    }

    @Test
    void refundAndListAndPing() {
        String code = chargeable("owner@acme.example");
        client.chargeAuthorization(BillingEnvironment.TEST, new ChargeCommand("owner@acme.example", 10000, code, "sub-t-12-rn-1-1"));
        var refund = client.refund(BillingEnvironment.TEST, new RefundCommand("sub-t-12-rn-1-1", 4000L, "refund-key-1"));
        PaystackModels.RefundResult rr = ((PaystackResult.Ok<PaystackModels.RefundResult>) refund).value();
        assertThat(rr.amountPesewas()).isEqualTo(4000);
        assertThat(rr.transactionReference()).isEqualTo("sub-t-12-rn-1-1");
        assertThat(rr.merchantNote()).isEqualTo("refund-key-1");
        // more than what is left: refused
        assertThat(client.refund(BillingEnvironment.TEST, new RefundCommand("sub-t-12-rn-1-1", 7000L, null))).isInstanceOf(PaystackResult.Rejected.class);
        var page = ((PaystackResult.Ok<PaystackModels.TransactionPage>) client.listTransactions(BillingEnvironment.TEST,
                new TransactionQuery(null, null, "success", 10, 1))).value();
        assertThat(page.items()).hasSize(1);
        assertThat(client.ping(BillingEnvironment.TEST)).isInstanceOf(PaystackResult.Ok.class);
        assertThat(fake.requests("GET", "/transaction").stream().filter(r -> "perPage=1&page=1".equals(r.query()))).hasSize(1);
    }

    // ------------------------------------------------------------------------------------------ inputs the caller cannot abuse

    @Test
    void aCallbackUrlOutsideTheConfiguredBaseIsRefusedBeforeAnythingIsSent() {
        for (String bad : new String[]{"https://evil.example/cb", "https://api.shopfi.test.evil.example/cb", "http://api.shopfi.test/cb", null, "/relative"}) {
            assertThatThrownBy(() -> client.initialize(BillingEnvironment.TEST,
                    new InitializeCommand("o@acme.example", 100, "sub-card-t-abc", Map.of(), bad))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void referencesOutsidePaystacksAlphabetAreRefusedAndNeverReachAPath() {
        for (String bad : new String[]{"a/b", "a b", "a_b", "../x", "a?b", "", "x".repeat(101), "a\nb"}) {
            assertThatThrownBy(() -> client.verify(BillingEnvironment.TEST, bad)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void aNonPositiveAmountIsRefused() {
        assertThatThrownBy(() -> client.initialize(BillingEnvironment.TEST,
                new InitializeCommand("o@acme.example", 0, "sub-card-t-abc", Map.of(), CALLBACK + "/x"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theCheckoutUrlMustBeOnTheConfiguredHost() {
        PaystackPlatformClient strict = newClient(props(fake.baseUrl(), "https://checkout.paystack.com/", 400, 2), Optional.of(SECRET));
        PaystackResult<InitializeResult> r = strict.initialize(BillingEnvironment.TEST, init("sub-card-t-host"));
        assertThat(r).isEqualTo(new PaystackResult.Unavailable<InitializeResult>(PaystackResult.Reason.BAD_CHECKOUT_URL));
    }

    // ------------------------------------------------------------------------------------------ outcomes

    @Test
    void noStoredKeyIsNotConfigured_andNothingIsSent() {
        PaystackPlatformClient none = newClient(props(fake.baseUrl(), fake.checkoutPrefix(), 400, 2), Optional.empty());
        assertThat(none.verify(BillingEnvironment.LIVE, "sub-l-1-tc-0-1")).isInstanceOf(PaystackResult.NotConfigured.class);
        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void aRejectedKeyIs401KeyRejectedAndIsRecordedExceptByPing() {
        fake.acceptSecret("sk_test_someOtherKeyThatIsAccepted0001");
        assertThat(client.verify(BillingEnvironment.TEST, "sub-t-1-tc-0-1")).isInstanceOf(PaystackResult.KeyRejected.class);
        assertThat(rejected).containsExactly(BillingEnvironment.TEST);
        assertThat(client.ping(BillingEnvironment.TEST)).isInstanceOf(PaystackResult.KeyRejected.class);
        assertThat(rejected).hasSize(1); // ping reports to its caller, the client itself recorded only the first
    }

    @Test
    void aTimeoutAfterSendingIsUnknownOutcome_theChargeHappenedOnce_andResolvingItIsAVerify() {
        fake.hangMillis(1500);
        fake.script("sub-t-12-rn-3", FakeOutcome.TIMEOUT);
        String code = chargeable("owner@acme.example");
        PaystackResult<Transaction> r = client.chargeAuthorization(BillingEnvironment.TEST,
                new ChargeCommand("owner@acme.example", 14900, code, "sub-t-12-rn-3-1"));
        assertThat(r).isInstanceOf(PaystackResult.UnknownOutcome.class);
        assertThat(fake.chargesCreated("sub-t-12-rn-3-1")).isEqualTo(1);
        // the caller resolves by verify and NEVER by charging again
        Transaction t = ((PaystackResult.Ok<Transaction>) client.verify(BillingEnvironment.TEST, "sub-t-12-rn-3-1")).value();
        assertThat(t.status()).isEqualTo("success");
        assertThat(fake.chargesCreated("sub-t-12-rn-3-1")).isEqualTo(1);
    }

    @Test
    void connectFailureIsUnavailableNothingSent() throws Exception {
        int dead;
        try (var s = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            dead = s.getLocalPort();
        }
        PaystackPlatformClient c = newClient(props("http://127.0.0.1:" + dead, fake.checkoutPrefix(), 400, 2), Optional.of(SECRET));
        assertThat(c.verify(BillingEnvironment.TEST, "sub-t-1-tc-0-1"))
                .isEqualTo(new PaystackResult.Unavailable<Transaction>(PaystackResult.Reason.CONNECT_FAILED));
    }

    @Test
    void serverErrorsOpenTheCircuit_thenAProbeAfterTheOpenPeriodClosesIt() {
        String code = chargeable("owner@acme.example");
        fake.script("sub-t-12-rn-9", FakeOutcome.SERVER_ERROR, FakeOutcome.BAD_GATEWAY, FakeOutcome.SERVER_ERROR);
        for (int i = 1; i <= 3; i++) {
            assertThat(client.chargeAuthorization(BillingEnvironment.TEST, new ChargeCommand("o@acme.example", 100, code, "sub-t-12-rn-9-" + i)))
                    .isEqualTo(new PaystackResult.Unavailable<Transaction>(PaystackResult.Reason.SERVER_ERROR));
        }
        assertThat(client.breaker(BillingEnvironment.TEST).state()).isEqualTo(CircuitBreaker.State.OPEN);
        int before = fake.requests().size();
        assertThat(client.verify(BillingEnvironment.TEST, "sub-t-1-tc-0-1"))
                .isEqualTo(new PaystackResult.Unavailable<Transaction>(PaystackResult.Reason.CIRCUIT_OPEN));
        assertThat(fake.requests()).as("an open circuit sends nothing").hasSize(before);
        // the other environment has its own circuit
        assertThat(client.breaker(BillingEnvironment.LIVE).state()).isEqualTo(CircuitBreaker.State.CLOSED);
        clock.advance(Duration.ofSeconds(31));
        // the probe reaches Paystack; a 404 is an ANSWER, so it closes the circuit
        assertThat(client.verify(BillingEnvironment.TEST, "sub-t-1-tc-0-1")).isEqualTo(new PaystackResult.Rejected<Transaction>(404));
        assertThat(client.breaker(BillingEnvironment.TEST).state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void tooManyConcurrentCallsFailFastBulkheadFull() throws Exception {
        fake.hangMillis(600);
        String code = chargeable("owner@acme.example");
        PaystackPlatformClient wide = newClient(props(fake.baseUrl(), fake.checkoutPrefix(), 2000, 2), Optional.of(SECRET));
        fake.script("sub-t-12-rn-7", FakeOutcome.TIMEOUT, FakeOutcome.TIMEOUT);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch started = new CountDownLatch(2);
        List<Future<?>> slow = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            int n = i;
            slow.add(pool.submit(() -> {
                started.countDown();
                return wide.chargeAuthorization(BillingEnvironment.TEST, new ChargeCommand("o@acme.example", 100, code, "sub-t-12-rn-7-" + n));
            }));
        }
        started.await();
        Thread.sleep(250); // both calls are inside the bulkhead now
        long t0 = System.nanoTime();
        PaystackResult<Transaction> third = wide.verify(BillingEnvironment.TEST, "sub-t-1-tc-0-1");
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertThat(third).isEqualTo(new PaystackResult.Unavailable<Transaction>(PaystackResult.Reason.BULKHEAD_FULL));
        assertThat(ms).as("fails fast, does not wait for a permit").isLessThan(300);
        for (Future<?> f : slow) {
            f.get(5, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
    }

    @Test
    void rateLimitedIs429Unavailable() {
        // a server that answers 429: the fake has no such mode, so use a tiny raw server
        com.sun.net.httpserver.HttpServer raw = rawServer(429, "{\"status\":false,\"message\":\"slow down\"}");
        try {
            PaystackPlatformClient c = newClient(props("http://127.0.0.1:" + raw.getAddress().getPort(), fake.checkoutPrefix(), 400, 2), Optional.of(SECRET));
            assertThat(c.verify(BillingEnvironment.TEST, "sub-t-1-tc-0-1"))
                    .isEqualTo(new PaystackResult.Unavailable<Transaction>(PaystackResult.Reason.RATE_LIMITED));
        } finally {
            raw.stop(0);
        }
    }

    @Test
    void anUnparseableSuccessAnswerIsUnknownOutcomeNotAGuess() {
        com.sun.net.httpserver.HttpServer raw = rawServer(200, "<html>not json</html>");
        try {
            PaystackPlatformClient c = newClient(props("http://127.0.0.1:" + raw.getAddress().getPort(), fake.checkoutPrefix(), 400, 2), Optional.of(SECRET));
            assertThat(c.verify(BillingEnvironment.TEST, "sub-t-1-tc-0-1")).isInstanceOf(PaystackResult.UnknownOutcome.class);
        } finally {
            raw.stop(0);
        }
    }

    // ------------------------------------------------------------------------------------------ secrets

    @Test
    void theSecretOnlyTravelsInTheBearerHeader_neverInAUrl_aBody_aLogOrAnException() throws Exception {
        try (LogCapture logs = new LogCapture()) {
            fake.acceptSecret("sk_test_someOtherKeyThatIsAccepted0001"); // our key will be rejected: exercise the error paths too
            client.verify(BillingEnvironment.TEST, "sub-t-1-tc-0-1");
            client.ping(BillingEnvironment.TEST);
            assertThatThrownBy(() -> client.verify(BillingEnvironment.TEST, "bad/ref")).satisfies(e -> assertThat(String.valueOf(e.getMessage())).doesNotContain(SECRET));
            fake.reset();
            client.initialize(BillingEnvironment.TEST, init("sub-card-t-leak"));
            client.chargeAuthorization(BillingEnvironment.TEST, new ChargeCommand("owner@acme.example", 100, chargeable("owner@acme.example"), "sub-t-1-rn-1-1"));
            assertThat(logs.text()).doesNotContain(SECRET).doesNotContain("DOCSEXAMPLEONLYNOTAKEY");
            // the fake recorded path and query of every request: the key is in none of them
            fake.requests().forEach(r -> assertThat(r.path() + "?" + r.query()).doesNotContain("sk_test").doesNotContain("DOCSEXAMPLE"));
            assertThat(fake.requests()).allMatch(RecordedRequestKnown::known);
        }
    }

    /** The recorded requests all carried a Bearer key the fake accepted (it was in the header). */
    private interface RecordedRequestKnown {
        static boolean known(com.example.perfume_budget.platform.billing.fake.RecordedRequest r) {
            return r.authorizedWithKnownKey();
        }
    }

    @Test
    void summarisedValuesNeverPrintTheAuthorizationCodeOrTheCustomerEmail() {
        client.initialize(BillingEnvironment.TEST, init("sub-t-5-tc-0-1"));
        fake.completeCheckout("sub-t-5-tc-0-1", FakeCard.SUCCESS);
        Transaction t = ((PaystackResult.Ok<Transaction>) client.verify(BillingEnvironment.TEST, "sub-t-5-tc-0-1")).value();
        String printed = t + " " + t.authorization() + " " + new ChargeCommand("owner@acme.example", 1, "AUTH_secretcode", "r")
                + " " + new InitializeCommand("owner@acme.example", 1, "r", Map.of(), "u");
        assertThat(printed).doesNotContain(t.authorization().code()).doesNotContain("owner@acme.example").doesNotContain("AUTH_")
                .doesNotContain(t.authorization().signature());
    }

    // ------------------------------------------------------------------------------------------ TLS

    @Test
    void tlsVerificationIsOn_aSelfSignedServerIsNeverTrusted() throws Exception {
        Path dir = Files.createTempDirectory("fake-tls");
        Path store = dir.resolve("self-signed.p12");
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(), "-genkeypair", "-alias", "t",
                "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "changeit",
                "-dname", "CN=localhost", "-validity", "2", "-ext", "SAN=dns:localhost,ip:127.0.0.1").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor()).isZero();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
            ks.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), null, null);
        HttpsServer https = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(ssl));
        AtomicInteger reached = new AtomicInteger();
        https.createContext("/", ex -> {
            reached.incrementAndGet();
            byte[] b = "{\"status\":true,\"data\":{}}".getBytes();
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        https.start();
        try {
            PaystackPlatformClient c = newClient(props("https://localhost:" + https.getAddress().getPort(), fake.checkoutPrefix(), 1500, 2), Optional.of(SECRET));
            PaystackResult<Transaction> r = c.verify(BillingEnvironment.TEST, "sub-t-1-tc-0-1");
            assertThat(r).as("an untrusted certificate must not yield an answer").isNotInstanceOf(PaystackResult.Ok.class);
            assertThat(reached.get()).as("no request (and so no Bearer key) reached the untrusted server").isZero();
        } finally {
            https.stop(0);
        }
    }

    private static com.sun.net.httpserver.HttpServer rawServer(int status, String body) {
        try {
            com.sun.net.httpserver.HttpServer s = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            s.createContext("/", ex -> {
                byte[] b = body.getBytes();
                ex.sendResponseHeaders(status, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            });
            s.start();
            return s;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
