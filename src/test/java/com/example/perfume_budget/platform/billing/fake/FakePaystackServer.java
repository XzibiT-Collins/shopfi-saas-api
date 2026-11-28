package com.example.perfume_budget.platform.billing.fake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The FAKE Paystack (BILL-01 AC7, billing.md 19.1). Lives in TEST sources only: it is never part of the production jar
 * ({@code FakePaystackNotInProductionTest} fails the build if a fake class reaches {@code target/classes} or the jar).
 *
 * <p>Usable from JUnit (JVM-embedded: {@link #start()} on a random port, {@link #shared()} one per JVM) and as a standalone
 * process ({@link FakePaystackMain}) for the frontend real-stack e2e. The application reaches it by pointing
 * {@code billing.paystack.<env>-base-url} (and, outside production, {@code billing.paystack.checkout-url-prefix}) at it.
 *
 * <p>Endpoints (shapes copied from the Paystack documentation): {@code POST /transaction/initialize},
 * {@code GET /transaction/verify/{reference}}, {@code POST /transaction/charge_authorization}, {@code POST /refund},
 * {@code GET /transaction} (list), {@code POST /customer}, {@code GET /customer/{code}},
 * {@code POST /customer/deactivate_authorization}, the hosted checkout {@code GET /checkout/{accessCode}} and
 * {@code POST /checkout/{accessCode}/pay} (completes or declines per the chosen magic card, mints the authorization and
 * redirects to the callback URL), and the control API under {@code /__control/} (script, webhook, requests, reset, config).
 *
 * <p>Scripted outcomes: the magic card chosen at checkout decides what its authorization does later; {@link #script} queues
 * {@link FakeOutcome}s for the next charges of a reference prefix. Webhooks carry a REAL HMAC-SHA512 of the exact body
 * with the configured secret and can be duplicated, delayed, reordered, dropped or sent with a bad signature. Every request
 * is recorded. Two charges with the same reference are ONE charge: the second is Paystack's "Duplicate Transaction
 * Reference" (400).
 */
public final class FakePaystackServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static FakePaystackServer shared;

    public static synchronized FakePaystackServer shared() {
        if (shared == null) {
            shared = start();
        }
        return shared;
    }

    public static FakePaystackServer start() {
        return start(0);
    }

    public static FakePaystackServer start(int port) {
        try {
            return new FakePaystackServer(port);
        } catch (IOException e) {
            throw new IllegalStateException("The fake Paystack could not start on port " + port, e);
        }
    }

    // ---------------------------------------------------------------------------------------------- state

    private static final class Auth {
        final String code = "AUTH_" + random(10);
        final FakeCard card;
        final String email;
        boolean active = true;

        Auth(FakeCard card, String email) {
            this.card = card;
            this.email = email;
        }
    }

    private static final class Tx {
        long id;
        String reference;
        long amount;
        String currency = "GHS";
        String status;            // pending | success | failed | abandoned
        String gateway = "Pending";
        String email;
        String accessCode;
        String callbackUrl;
        Instant paidAt;
        Instant createdAt;
        Auth auth;
        FakeOutcome scenario = FakeOutcome.SUCCESS;
        long refunded;
        ObjectNode metadata;
    }

    private final HttpServer server;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "fake-paystack-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient webhookClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Map<String, Tx> byReference = new ConcurrentHashMap<>();
    private final Map<String, Tx> byAccessCode = new ConcurrentHashMap<>();
    private final Map<String, Auth> auths = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> customers = new ConcurrentHashMap<>();
    private final Map<String, Deque<FakeOutcome>> scripts = new ConcurrentHashMap<>();
    private final Map<String, Integer> chargeCounts = new ConcurrentHashMap<>();
    private final List<RecordedRequest> recorded = new CopyOnWriteArrayList<>();
    private final List<String> sentWebhooks = new CopyOnWriteArrayList<>();
    private final Set<String> secrets = ConcurrentHashMap.newKeySet();
    private final AtomicLong ids = new AtomicLong(1000);
    private volatile String webhookUrl;
    private volatile String webhookSecret;
    private volatile long delayedSeconds = 2;
    private volatile long hangMillis = 30_000;
    private volatile int forcedStatus;

    private FakePaystackServer(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-paystack-http");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", this::dispatch);
        server.start();
    }

    // ---------------------------------------------------------------------------------------------- public (JUnit) API

    public int port() {
        return server.getAddress().getPort();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    /** The prefix every {@code authorization_url} of the fake starts with (point {@code billing.paystack.checkout-url-prefix} at it). */
    public String checkoutPrefix() {
        return baseUrl() + "/checkout/";
    }

    /** A secret key the fake accepts as Bearer. With none registered the fake accepts any {@code sk_test_} / {@code sk_live_} key. */
    public FakePaystackServer acceptSecret(String secretKey) {
        secrets.add(secretKey);
        return this;
    }

    /** Where webhooks go and the secret that signs them. */
    public FakePaystackServer webhookTarget(String url, String signingSecret) {
        this.webhookUrl = url;
        this.webhookSecret = signingSecret;
        return this;
    }

    /** Seconds a DELAYED charge stays pending. */
    public FakePaystackServer delayedSeconds(long seconds) {
        this.delayedSeconds = seconds;
        return this;
    }

    /** How long the TIMEOUT outcome holds the connection (the client's read timeout must be shorter). */
    public FakePaystackServer hangMillis(long millis) {
        this.hangMillis = millis;
        return this;
    }

    /** Every API request answers this HTTP status (a Paystack outage); 0 ends the outage. The control API is unaffected. */
    public FakePaystackServer outage(int httpStatus) {
        this.forcedStatus = httpStatus;
        return this;
    }

    /** Queues outcomes for the next charges whose reference starts with {@code referencePrefix}. */
    public FakePaystackServer script(String referencePrefix, FakeOutcome... outcomes) {
        Deque<FakeOutcome> q = scripts.computeIfAbsent(referencePrefix, k -> new ArrayDeque<>());
        synchronized (q) {
            Collections.addAll(q, outcomes);
        }
        return this;
    }

    public List<RecordedRequest> requests() {
        return List.copyOf(recorded);
    }

    public List<RecordedRequest> requests(String method, String pathPrefix) {
        return recorded.stream().filter(r -> r.method().equals(method) && r.path().startsWith(pathPrefix)).toList();
    }

    /** How many times a charge was actually CREATED for the reference (idempotency: never more than 1). */
    public int chargesCreated(String reference) {
        return chargeCounts.getOrDefault(reference, 0);
    }

    public int totalChargesCreated() {
        return chargeCounts.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** The bodies of the webhooks the fake SENT (never dropped ones), for assertions. */
    public List<String> sentWebhookBodies() {
        return List.copyOf(sentWebhooks);
    }

    public void reset() {
        byReference.clear();
        byAccessCode.clear();
        auths.clear();
        customers.clear();
        scripts.clear();
        chargeCounts.clear();
        recorded.clear();
        sentWebhooks.clear();
        secrets.clear();
        webhookUrl = null;
        webhookSecret = null;
        delayedSeconds = 2;
        hangMillis = 30_000;
        forcedStatus = 0;
    }

    /** Mints a reusable authorization for a card without going through the checkout (test setup). Returns the code. */
    public String mintAuthorization(FakeCard card, String email) {
        Auth a = new Auth(card, email);
        auths.put(a.code, a);
        return a.code;
    }

    /** Completes a pending checkout transaction as the chosen card would, without the HTML page. */
    public void completeCheckout(String reference, FakeCard card) {
        Tx tx = byReference.get(reference);
        if (tx == null) {
            throw new IllegalArgumentException("unknown reference");
        }
        settleCheckout(tx, card);
    }

    /**
     * Fires an event for a known reference. {@code event}: {@code charge.success}, {@code charge.failed},
     * {@code refund.processed}. The body is the Paystack envelope {@code {"event":..,"data":{..transaction..}}}.
     */
    public String fireWebhook(String event, String reference, WebhookOptions options) {
        Tx tx = byReference.get(reference);
        if (tx == null) {
            throw new IllegalArgumentException("unknown reference");
        }
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("event", event);
        envelope.set("data", transactionJson(tx, false));
        return emit(JSON.valueToTree(envelope).toString(), options);
    }

    /** Sends several webhooks in REVERSE order of the given list (reordering). */
    public void fireReordered(List<String> events, String reference) {
        List<String> reversed = new ArrayList<>(events);
        Collections.reverse(reversed);
        for (String e : reversed) {
            fireWebhook(e, reference, WebhookOptions.once());
        }
    }

    /** Sends an arbitrary body with a real signature (or a bad one): for receiver tests that need an exact payload. */
    public String fireRaw(String exactBody, WebhookOptions options) {
        return emit(exactBody, options);
    }

    public static String hmacSha512Hex(String signingSecret, byte[] bytes) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        scheduler.shutdownNow();
    }

    // ---------------------------------------------------------------------------------------------- webhooks

    private String emit(String body, WebhookOptions o) {
        String url = webhookUrl;
        String signingSecret = webhookSecret;
        if (url == null || signingSecret == null) {
            throw new IllegalStateException("no webhook target configured");
        }
        if (o.drop()) {
            return body;
        }
        for (int i = 0; i < o.copies(); i++) {
            Runnable send = () -> {
                try {
                    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/json");
                    switch (o.signature()) {
                        case VALID -> b.header("x-paystack-signature", hmacSha512Hex(signingSecret, body.getBytes(StandardCharsets.UTF_8)));
                        case BAD -> b.header("x-paystack-signature", hmacSha512Hex(signingSecret, (body + " ").getBytes(StandardCharsets.UTF_8)));
                        case MISSING -> { }
                    }
                    webhookClient.send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding());
                    sentWebhooks.add(body);
                } catch (Exception e) {
                    // the receiver being down is a legitimate test scenario; the fake just does not retry
                }
            };
            if (o.delayMillis() > 0) {
                scheduler.schedule(send, o.delayMillis(), TimeUnit.MILLISECONDS);
            } else {
                send.run();
            }
        }
        return body;
    }

    // ---------------------------------------------------------------------------------------------- dispatch

    private void dispatch(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getRawQuery();
        byte[] raw = ex.getRequestBody().readAllBytes();
        try {
            if (path.startsWith("/__control/")) {
                control(ex, method, path, raw);
                return;
            }
            if (path.startsWith("/checkout/")) {
                recordRequest(method, path, query, null, null, true);
                checkout(ex, method, path, query, raw);
                return;
            }
            JsonNode body = raw.length == 0 ? JSON.createObjectNode() : JSON.readTree(raw);
            boolean known = authorized(ex);
            String reference = body.hasNonNull("reference") ? body.get("reference").asText()
                    : path.startsWith("/transaction/verify/") ? path.substring("/transaction/verify/".length()) : null;
            Long amount = body.hasNonNull("amount") ? body.get("amount").asLong() : null;
            recordRequest(method, path, query, reference, amount, known);
            if (forcedStatus != 0) {
                send(ex, forcedStatus, error("outage"));
                return;
            }
            if (!known) {
                send(ex, 401, error("Invalid key"));
                return;
            }
            route(ex, method, path, query, body);
        } catch (Exception e) {
            send(ex, 500, error("fake paystack error"));
        }
    }

    private void route(HttpExchange ex, String method, String path, String query, JsonNode body) throws Exception {
        if (method.equals("POST") && path.equals("/transaction/initialize")) {
            initialize(ex, body);
        } else if (method.equals("GET") && path.startsWith("/transaction/verify/")) {
            verify(ex, URLDecoder.decode(path.substring("/transaction/verify/".length()), StandardCharsets.UTF_8));
        } else if (method.equals("POST") && path.equals("/transaction/charge_authorization")) {
            charge(ex, body);
        } else if (method.equals("POST") && path.equals("/refund")) {
            refund(ex, body);
        } else if (method.equals("GET") && path.equals("/transaction")) {
            list(ex, query);
        } else if (method.equals("POST") && path.equals("/customer")) {
            createCustomer(ex, body);
        } else if (method.equals("GET") && path.startsWith("/customer/")) {
            Map<String, String> c = customers.get(path.substring("/customer/".length()));
            if (c == null) {
                send(ex, 404, error("Customer not found"));
            } else {
                send(ex, 200, ok("Customer retrieved", customerJson(c)));
            }
        } else if (method.equals("POST") && path.equals("/customer/deactivate_authorization")) {
            Auth a = auths.get(body.path("authorization_code").asText());
            if (a == null) {
                send(ex, 404, error("Authorization not found"));
            } else {
                a.active = false;
                send(ex, 200, ok("Authorization has been deactivated", null));
            }
        } else {
            send(ex, 404, error("Not found"));
        }
    }

    // ---------------------------------------------------------------------------------------------- endpoints

    private void initialize(HttpExchange ex, JsonNode body) throws IOException {
        String reference = body.path("reference").asText(null);
        if (reference == null || reference.isBlank() || !body.hasNonNull("amount") || !body.hasNonNull("email")) {
            send(ex, 400, error("Invalid Amount Sent"));
            return;
        }
        if (byReference.containsKey(reference)) {
            send(ex, 400, error("Duplicate Transaction Reference"));
            return;
        }
        Tx tx = new Tx();
        tx.id = ids.incrementAndGet();
        tx.reference = reference;
        tx.amount = body.get("amount").asLong();
        tx.currency = body.path("currency").asText("GHS");
        tx.email = body.get("email").asText();
        tx.status = "abandoned";
        tx.accessCode = random(12);
        tx.callbackUrl = body.path("callback_url").asText(null);
        tx.createdAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        tx.metadata = body.has("metadata") && body.get("metadata").isObject() ? (ObjectNode) body.get("metadata") : null;
        byReference.put(reference, tx);
        byAccessCode.put(tx.accessCode, tx);
        ObjectNode data = JSON.createObjectNode();
        data.put("authorization_url", checkoutPrefix() + tx.accessCode);
        data.put("access_code", tx.accessCode);
        data.put("reference", reference);
        send(ex, 200, ok("Authorization URL created", data));
    }

    private void verify(HttpExchange ex, String reference) throws IOException {
        Tx tx = byReference.get(reference);
        if (tx == null) {
            send(ex, 404, error("Transaction reference not found"));
            return;
        }
        send(ex, 200, ok("Verification successful", transactionJson(tx, true)));
    }

    private void charge(HttpExchange ex, JsonNode body) throws Exception {
        String reference = body.path("reference").asText(null);
        Auth auth = auths.get(body.path("authorization_code").asText());
        if (reference == null || reference.isBlank() || !body.hasNonNull("amount") || auth == null) {
            send(ex, 400, error("Invalid Authorization Code"));
            return;
        }
        if (!auth.active) {
            send(ex, 400, error("Authorization has been deactivated"));
            return;
        }
        if (!auth.card.reusable) {
            send(ex, 400, error("Authorization is not reusable"));
            return;
        }
        FakeOutcome outcome = next(reference, scenarioOf(auth.card));
        // idempotency: a reference is ONE charge. putIfAbsent makes two concurrent calls race to a single winner.
        Tx fresh = new Tx();
        fresh.id = ids.incrementAndGet();
        fresh.reference = reference;
        fresh.amount = body.get("amount").asLong();
        fresh.currency = body.path("currency").asText("GHS");
        fresh.email = body.path("email").asText(auth.email);
        fresh.auth = auth;
        fresh.scenario = outcome;
        fresh.createdAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        if (byReference.putIfAbsent(reference, fresh) != null) {
            send(ex, 400, error("Duplicate Transaction Reference"));
            return;
        }
        chargeCounts.merge(reference, 1, Integer::sum);
        switch (outcome) {
            case SERVER_ERROR -> {
                byReference.remove(reference); // nothing was processed
                chargeCounts.remove(reference);
                send(ex, 500, error("Internal Server Error"));
            }
            case BAD_GATEWAY -> {
                byReference.remove(reference);
                chargeCounts.remove(reference);
                send(ex, 502, error("Bad Gateway"));
            }
            case INSUFFICIENT_FUNDS -> {
                fail(fresh, "Insufficient Funds");
                send(ex, 200, ok("Charge attempted", transactionJson(fresh, false)));
            }
            case DECLINED -> {
                fail(fresh, "Declined");
                send(ex, 200, ok("Charge attempted", transactionJson(fresh, false)));
            }
            case TIMEOUT -> {
                succeed(fresh);
                Thread.sleep(hangMillis); // processed, but the answer comes too late
                send(ex, 200, ok("Charge attempted", transactionJson(fresh, false)));
            }
            case DELAYED -> {
                fresh.status = "pending";
                fresh.gateway = "Pending";
                scheduler.schedule(() -> {
                    succeed(fresh);
                    if (webhookUrl != null) {
                        fireWebhook("charge.success", reference, WebhookOptions.once());
                    }
                }, delayedSeconds, TimeUnit.SECONDS);
                send(ex, 200, ok("Charge attempted", transactionJson(fresh, false)));
            }
            default -> { // SUCCESS, WRONG_AMOUNT, WRONG_CURRENCY: charged; only verify lies for the wrong ones
                succeed(fresh);
                send(ex, 200, ok("Charge attempted", transactionJson(fresh, false)));
            }
        }
    }

    private void refund(HttpExchange ex, JsonNode body) throws IOException {
        Tx tx = byReference.get(body.path("transaction").asText());
        if (tx == null || !tx.status.equals("success")) {
            send(ex, 400, error("Transaction has not been successfully completed"));
            return;
        }
        long amount = body.hasNonNull("amount") ? body.get("amount").asLong() : tx.amount - tx.refunded;
        if (amount < 1 || tx.refunded + amount > tx.amount) {
            send(ex, 400, error("Amount is greater than the amount available for refund"));
            return;
        }
        tx.refunded += amount;
        ObjectNode data = JSON.createObjectNode();
        data.set("transaction", transactionJson(tx, false));
        data.put("id", ids.incrementAndGet());
        data.put("domain", "test");
        data.put("amount", amount);
        data.put("currency", tx.currency);
        data.put("status", "pending");
        data.put("channel", "card");
        data.put("fully_deducted", false);
        data.put("refunded_by", "fake@paystack.invalid");
        data.put("expected_at", Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.SECONDS).toString());
        if (body.hasNonNull("merchant_note")) {
            data.put("merchant_note", body.get("merchant_note").asText());
        }
        data.put("createdAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        send(ex, 200, ok("Refund has been queued for processing", data));
    }

    private void list(HttpExchange ex, String query) throws IOException {
        Map<String, String> q = parseQuery(query);
        int perPage = Math.max(1, Math.min(100, Integer.parseInt(q.getOrDefault("perPage", "50"))));
        int page = Math.max(1, Integer.parseInt(q.getOrDefault("page", "1")));
        String status = q.get("status");
        Instant from = q.containsKey("from") ? Instant.parse(q.get("from")) : null;
        Instant to = q.containsKey("to") ? Instant.parse(q.get("to")) : null;
        List<Tx> all = byReference.values().stream()
                .filter(t -> status == null || t.status.equals(status))
                .filter(t -> from == null || !t.createdAt.isBefore(from))
                .filter(t -> to == null || !t.createdAt.isAfter(to))
                .sorted((a, b) -> Long.compare(b.id, a.id)).toList();
        ArrayNode data = JSON.createArrayNode();
        all.stream().skip((long) (page - 1) * perPage).limit(perPage).forEach(t -> data.add(transactionJson(t, true)));
        ObjectNode root = JSON.createObjectNode();
        root.put("status", true);
        root.put("message", "Transactions retrieved");
        root.set("data", data);
        ObjectNode meta = root.putObject("meta");
        meta.put("total", all.size());
        meta.put("skipped", (page - 1) * perPage);
        meta.put("perPage", perPage);
        meta.put("page", page);
        meta.put("pageCount", (all.size() + perPage - 1) / perPage);
        send(ex, 200, root);
    }

    private void createCustomer(HttpExchange ex, JsonNode body) throws IOException {
        if (!body.hasNonNull("email")) {
            send(ex, 400, error("Email is required"));
            return;
        }
        Map<String, String> c = new LinkedHashMap<>();
        c.put("email", body.get("email").asText());
        c.put("customer_code", "CUS_" + random(11));
        c.put("id", Long.toString(ids.incrementAndGet()));
        customers.put(c.get("customer_code"), c);
        send(ex, 200, ok("Customer created", customerJson(c)));
    }

    // ---------------------------------------------------------------------------------------------- hosted checkout

    private void checkout(HttpExchange ex, String method, String path, String query, byte[] raw) throws IOException {
        String rest = path.substring("/checkout/".length());
        boolean pay = rest.endsWith("/pay");
        String accessCode = pay ? rest.substring(0, rest.length() - "/pay".length()) : rest;
        Tx tx = byAccessCode.get(accessCode);
        if (tx == null) {
            sendHtml(ex, 404, "<html><body><h1>Unknown checkout</h1></body></html>");
            return;
        }
        if (!pay && method.equals("GET")) {
            StringBuilder h = new StringBuilder("<html><head><title>Fake Paystack checkout</title></head><body>")
                    .append("<h1>Fake Paystack checkout</h1><p>Reference ").append(tx.reference).append(", GHS ")
                    .append(tx.amount / 100).append('.').append(String.format("%02d", tx.amount % 100))
                    .append("</p><form method=\"post\" action=\"/checkout/").append(accessCode).append("/pay\">");
            for (FakeCard c : FakeCard.values()) {
                h.append("<label><input type=\"radio\" name=\"card\" value=\"").append(c.name()).append("\"")
                        .append(c == FakeCard.SUCCESS ? " checked" : "").append("> ").append(c.name().replace('_', ' ').toLowerCase())
                        .append(" (").append(c.number).append(")</label><br>");
            }
            h.append("<button type=\"submit\">Pay</button></form></body></html>");
            sendHtml(ex, 200, h.toString());
            return;
        }
        if (pay && method.equals("POST")) {
            Map<String, String> form = parseQuery(new String(raw, StandardCharsets.UTF_8));
            Map<String, String> q = parseQuery(query);
            String cardName = form.getOrDefault("card", q.getOrDefault("card", "SUCCESS"));
            FakeCard card;
            try {
                card = FakeCard.valueOf(cardName);
            } catch (IllegalArgumentException e) {
                sendHtml(ex, 400, "<html><body>unknown card</body></html>");
                return;
            }
            settleCheckout(tx, card);
            String target = tx.callbackUrl == null ? "/checkout/" + accessCode
                    : tx.callbackUrl + (tx.callbackUrl.contains("?") ? "&" : "?") + "trxref=" + tx.reference + "&reference=" + tx.reference;
            ex.getResponseHeaders().add("Location", target);
            ex.sendResponseHeaders(302, -1);
            ex.close();
            return;
        }
        sendHtml(ex, 405, "<html><body>method not allowed</body></html>");
    }

    private void settleCheckout(Tx tx, FakeCard card) {
        Auth a = new Auth(card, tx.email);
        tx.scenario = scenarioOf(card);
        switch (card) {
            case DECLINED -> fail(tx, "Declined");
            case INSUFFICIENT_FUNDS -> fail(tx, "Insufficient Funds");
            default -> {
                tx.auth = a;
                auths.put(a.code, a);
                succeed(tx);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private static FakeOutcome scenarioOf(FakeCard c) {
        return switch (c) {
            case SUCCESS, NON_REUSABLE -> FakeOutcome.SUCCESS;
            case INSUFFICIENT_FUNDS -> FakeOutcome.INSUFFICIENT_FUNDS;
            case DECLINED -> FakeOutcome.DECLINED;
            case TIMEOUT -> FakeOutcome.TIMEOUT;
            case SERVER_ERROR -> FakeOutcome.SERVER_ERROR;
            case WRONG_AMOUNT -> FakeOutcome.WRONG_AMOUNT;
            case WRONG_CURRENCY -> FakeOutcome.WRONG_CURRENCY;
            case DELAYED -> FakeOutcome.DELAYED;
        };
    }

    private FakeOutcome next(String reference, FakeOutcome fallback) {
        for (Map.Entry<String, Deque<FakeOutcome>> e : scripts.entrySet()) {
            if (reference.startsWith(e.getKey())) {
                synchronized (e.getValue()) {
                    FakeOutcome o = e.getValue().pollFirst();
                    if (o != null) {
                        return o;
                    }
                }
            }
        }
        return fallback;
    }

    private static void succeed(Tx tx) {
        tx.status = "success";
        tx.gateway = "Successful";
        tx.paidAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    private static void fail(Tx tx, String gatewayResponse) {
        tx.status = "failed";
        tx.gateway = gatewayResponse;
    }

    /** {@code forVerify}: the WRONG_AMOUNT / WRONG_CURRENCY scenarios report other values than were charged. */
    private ObjectNode transactionJson(Tx tx, boolean forVerify) {
        ObjectNode d = JSON.createObjectNode();
        long amount = tx.amount;
        String currency = tx.currency;
        if (forVerify && tx.scenario == FakeOutcome.WRONG_AMOUNT) {
            amount = tx.amount + 1;
        }
        if (forVerify && tx.scenario == FakeOutcome.WRONG_CURRENCY) {
            currency = "USD";
        }
        d.put("id", tx.id);
        d.put("domain", "test");
        d.put("status", tx.status);
        d.put("reference", tx.reference);
        d.put("receipt_number", (String) null);
        d.put("amount", amount);
        d.putNull("message");
        d.put("gateway_response", tx.gateway);
        if (tx.paidAt != null) {
            d.put("paid_at", tx.paidAt.toString());
        } else {
            d.putNull("paid_at");
        }
        d.put("created_at", tx.createdAt.toString());
        d.put("channel", "card");
        d.put("currency", currency);
        d.put("ip_address", "127.0.0.1");
        if (tx.metadata != null) {
            d.set("metadata", tx.metadata);
        } else {
            d.put("metadata", "");
        }
        d.putNull("log");
        d.put("fees", Math.round(tx.amount * 0.015));
        if (tx.auth != null && tx.status.equals("success")) {
            ObjectNode a = d.putObject("authorization");
            a.put("authorization_code", tx.auth.code);
            a.put("bin", tx.auth.card.bin);
            a.put("last4", tx.auth.card.last4);
            a.put("exp_month", "12");
            a.put("exp_year", "2030");
            a.put("channel", "card");
            a.put("card_type", tx.auth.card.brand + " ");
            a.put("bank", "TEST BANK");
            a.put("country_code", "GH");
            a.put("brand", tx.auth.card.brand);
            a.put("reusable", tx.auth.card.reusable);
            a.put("signature", "SIG_" + tx.auth.code.substring(5));
            a.putNull("account_name");
        } else {
            d.putObject("authorization");
        }
        ObjectNode c = d.putObject("customer");
        c.put("id", 1);
        c.putNull("first_name");
        c.putNull("last_name");
        c.put("email", tx.email);
        c.put("customer_code", "CUS_" + Integer.toHexString(tx.email.hashCode()).toLowerCase());
        c.putNull("phone");
        c.putNull("metadata");
        c.put("risk_action", "default");
        d.putNull("plan");
        d.put("requested_amount", tx.amount);
        return d;
    }

    private static ObjectNode customerJson(Map<String, String> c) {
        ObjectNode n = JSON.createObjectNode();
        n.put("email", c.get("email"));
        n.put("customer_code", c.get("customer_code"));
        n.put("id", Long.parseLong(c.get("id")));
        n.put("integration", 100000);
        n.put("domain", "test");
        return n;
    }

    private boolean authorized(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.startsWith("Bearer ")) {
            return false;
        }
        String key = h.substring("Bearer ".length());
        return secrets.isEmpty() ? key.startsWith("sk_test_") || key.startsWith("sk_live_") : secrets.contains(key);
    }

    private void recordRequest(String method, String path, String query, String reference, Long amount, boolean known) {
        recorded.add(new RecordedRequest(Instant.now(), method, path, query, reference, amount, known));
    }

    private static ObjectNode ok(String message, JsonNode data) {
        ObjectNode n = JSON.createObjectNode();
        n.put("status", true);
        n.put("message", message);
        if (data != null) {
            n.set("data", data);
        }
        return n;
    }

    private static ObjectNode error(String message) {
        ObjectNode n = JSON.createObjectNode();
        n.put("status", false);
        n.put("message", message);
        return n;
    }

    private static void send(HttpExchange ex, int status, JsonNode json) throws IOException {
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static void sendHtml(HttpExchange ex, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> out = new HashMap<>();
        if (q == null || q.isEmpty()) {
            return out;
        }
        for (String pair : q.split("&")) {
            int i = pair.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }

    private static String random(int n) {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------------------------------- control API

    private void control(HttpExchange ex, String method, String path, byte[] raw) throws IOException {
        JsonNode body = raw.length == 0 ? JSON.createObjectNode() : JSON.readTree(raw);
        switch (path) {
            case "/__control/reset" -> {
                reset();
                send(ex, 200, ok("reset", null));
            }
            case "/__control/config" -> {
                if (body.hasNonNull("secret")) {
                    acceptSecret(body.get("secret").asText());
                }
                if (body.hasNonNull("webhookUrl") && body.hasNonNull("webhookSecret")) {
                    webhookTarget(body.get("webhookUrl").asText(), body.get("webhookSecret").asText());
                }
                if (body.hasNonNull("delayedSeconds")) {
                    delayedSeconds(body.get("delayedSeconds").asLong());
                }
                if (body.hasNonNull("hangMillis")) {
                    hangMillis(body.get("hangMillis").asLong());
                }
                send(ex, 200, ok("configured", null));
            }
            case "/__control/script" -> {
                List<FakeOutcome> outcomes = new ArrayList<>();
                body.path("outcomes").forEach(o -> outcomes.add(FakeOutcome.valueOf(o.asText())));
                script(body.path("prefix").asText(), outcomes.toArray(FakeOutcome[]::new));
                send(ex, 200, ok("scripted", null));
            }
            case "/__control/webhook" -> {
                WebhookOptions o = new WebhookOptions(body.path("copies").asInt(1), body.path("delayMs").asLong(0),
                        WebhookOptions.Signature.valueOf(body.path("signature").asText("VALID")), body.path("drop").asBoolean(false));
                fireWebhook(body.path("event").asText("charge.success"), body.path("reference").asText(), o);
                send(ex, 200, ok("fired", null));
            }
            case "/__control/requests" -> {
                ArrayNode a = JSON.createArrayNode();
                for (RecordedRequest r : recorded) {
                    ObjectNode n = a.addObject();
                    n.put("method", r.method());
                    n.put("path", r.path());
                    n.put("reference", r.reference());
                    if (r.amount() != null) {
                        n.put("amount", r.amount());
                    }
                }
                send(ex, 200, ok("requests", a));
            }
            default -> send(ex, 404, error("Not found"));
        }
    }
}
