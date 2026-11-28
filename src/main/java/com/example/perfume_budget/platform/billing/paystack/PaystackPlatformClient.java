package com.example.perfume_budget.platform.billing.paystack;

import com.example.perfume_budget.platform.billing.BillingEnvironment;
import com.example.perfume_budget.platform.billing.BillingProperties;
import com.example.perfume_budget.platform.billing.paystack.PaystackModels.Authorization;
import com.example.perfume_budget.platform.billing.paystack.PaystackModels.ChargeCommand;
import com.example.perfume_budget.platform.billing.paystack.PaystackModels.InitializeCommand;
import com.example.perfume_budget.platform.billing.paystack.PaystackModels.InitializeResult;
import com.example.perfume_budget.platform.billing.paystack.PaystackModels.RefundCommand;
import com.example.perfume_budget.platform.billing.paystack.PaystackModels.RefundResult;
import com.example.perfume_budget.platform.billing.paystack.PaystackModels.Transaction;
import com.example.perfume_budget.platform.billing.paystack.PaystackModels.TransactionPage;
import com.example.perfume_budget.platform.billing.paystack.PaystackModels.TransactionQuery;
import com.example.perfume_budget.platform.billing.paystack.PaystackResult.Reason;
import com.example.perfume_budget.platform.billing.settings.BillingSettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/**
 * Client of the PLATFORM's own Paystack account (BILL-01 AC6, billing.md 21 item 26). It is NOT the shops' Paystack
 * gateway service and never touches a shop's gateway configuration: keys come from
 * {@link CredentialAccess} (the platform billing credentials of the environment) at call time.
 *
 * <ul>
 *   <li><b>Transport:</b> the JDK {@link HttpClient} with the default TLS trust (certificate and host name verification stay
 *       on), a connect timeout and a per-request read timeout; the base URL is configuration only
 *       ({@link BillingProperties}), never input; the reference that goes into a path is checked against Paystack's alphabet.</li>
 *   <li><b>Resilience:</b> a bulkhead (a bounded number of concurrent calls, FAIL FAST when full) and a circuit breaker per
 *       environment (5xx, 429, timeouts and connect failures count). No hidden retries: a money call is never repeated here.</li>
 *   <li><b>Outcome:</b> every call returns a {@link PaystackResult}; a timeout or a dropped connection after sending is
 *       {@link PaystackResult.UnknownOutcome}, resolved by {@code verify} and never by charging again (S4).</li>
 *   <li><b>Secrets:</b> the secret key is used for exactly one thing, the {@code Authorization: Bearer} header, and is held in a
 *       local that never reaches a log line, a message or a result. Responses are summarised into
 *       {@link PaystackModels} whose {@code toString} omits the e-mail and the authorization; raw bodies are never logged.</li>
 *   <li><b>URLs:</b> the callback URL must be under the configured callback base, and the checkout URL Paystack returns must
 *       start with the configured checkout prefix ({@code https://checkout.paystack.com/} in production, enforced at startup).</li>
 * </ul>
 */
@Slf4j
@Component
public class PaystackPlatformClient {

    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9.=-]{1,100}");
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final BillingProperties props;
    private final CredentialAccess credentials;
    private final BillingSettingsService settings;
    private final HttpClient http;
    private final Semaphore bulkhead;
    private final Map<BillingEnvironment, CircuitBreaker> breakers = new EnumMap<>(BillingEnvironment.class);
    private final MeterRegistry meters;
    private final int breakerThreshold;
    private final Duration breakerOpenFor;
    private final Clock breakerClock;

    @Autowired
    public PaystackPlatformClient(BillingProperties props, @Lazy CredentialAccess credentials,
                                  BillingSettingsService settings, Clock clock, MeterRegistry meters) {
        this(props, credentials, settings, clock, meters, 5, Duration.ofSeconds(30));
    }

    PaystackPlatformClient(BillingProperties props, CredentialAccess credentials, BillingSettingsService settings, Clock clock,
                           MeterRegistry meters, int breakerThreshold, Duration breakerOpenFor) {
        this.props = props;
        this.credentials = credentials;
        this.settings = settings;
        this.meters = meters;
        this.breakerThreshold = breakerThreshold;
        this.breakerOpenFor = breakerOpenFor;
        this.breakerClock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(props.connectTimeoutMillis()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.bulkhead = new Semaphore(props.maxConcurrentCalls());
        for (BillingEnvironment e : BillingEnvironment.values()) {
            breakers.put(e, new CircuitBreaker(breakerThreshold, breakerOpenFor, clock));
        }
    }

    /** Test support only: closes every circuit (the client is a singleton shared by cached test contexts). */
    public void resetCircuitsForTests() {
        for (BillingEnvironment e : BillingEnvironment.values()) {
            breakers.put(e, new CircuitBreaker(breakerThreshold, breakerOpenFor, breakerClock));
        }
    }

    CircuitBreaker breaker(BillingEnvironment environment) {
        return breakers.get(environment);
    }

    // ------------------------------------------------------------------------------------------ operations

    /** {@code POST /transaction/initialize}: channels from the settings, currency GHS, callback under the configured base. */
    public PaystackResult<InitializeResult> initialize(BillingEnvironment env, InitializeCommand cmd) {
        requireReference(cmd.reference());
        if (cmd.amountPesewas() < 1) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (cmd.callbackUrl() == null || !cmd.callbackUrl().startsWith(props.callbackBaseUrl() + "/")) {
            throw new IllegalArgumentException("the callback URL must be built from the configured callback base");
        }
        ObjectNode payload = JSON.createObjectNode();
        payload.put("email", cmd.email());
        payload.put("amount", cmd.amountPesewas());
        payload.put("currency", "GHS");
        payload.put("reference", cmd.reference());
        payload.put("callback_url", cmd.callbackUrl());
        var channels = payload.putArray("channels");
        settings.current().allowedChannels().forEach(channels::add);
        if (cmd.metadata() != null && !cmd.metadata().isEmpty()) {
            ObjectNode meta = payload.putObject("metadata");
            cmd.metadata().forEach(meta::put);
        }
        return call(env, "initialize", true, "POST", "/transaction/initialize", payload, d -> {
            String url = text(d, "authorization_url");
            return url == null ? null : new InitializeResult(url, text(d, "access_code"), text(d, "reference"));
        }, true);
    }

    /** {@code GET /transaction/verify/{reference}}. */
    public PaystackResult<Transaction> verify(BillingEnvironment env, String reference) {
        requireReference(reference);
        return call(env, "verify", true, "GET", "/transaction/verify/" + enc(reference), null, PaystackPlatformClient::transaction, false);
    }

    /** {@code POST /transaction/charge_authorization}: the reference is the idempotency key (Paystack refuses a repeat). */
    public PaystackResult<Transaction> chargeAuthorization(BillingEnvironment env, ChargeCommand cmd) {
        requireReference(cmd.reference());
        if (cmd.amountPesewas() < 1) {
            throw new IllegalArgumentException("amount must be positive");
        }
        ObjectNode payload = JSON.createObjectNode();
        payload.put("email", cmd.email());
        payload.put("amount", cmd.amountPesewas());
        payload.put("currency", "GHS");
        payload.put("reference", cmd.reference());
        payload.put("authorization_code", cmd.authorizationCode());
        return call(env, "charge", true, "POST", "/transaction/charge_authorization", payload, PaystackPlatformClient::transaction, false);
    }

    /** {@code POST /refund}: {@code merchant_note} carries our refund key so a refund event can be matched (12.5). */
    public PaystackResult<RefundResult> refund(BillingEnvironment env, RefundCommand cmd) {
        requireReference(cmd.transactionReference());
        ObjectNode payload = JSON.createObjectNode();
        payload.put("transaction", cmd.transactionReference());
        if (cmd.amountPesewas() != null) {
            payload.put("amount", cmd.amountPesewas());
        }
        payload.put("currency", "GHS");
        if (cmd.merchantNote() != null) {
            payload.put("merchant_note", cmd.merchantNote());
        }
        return call(env, "refund", true, "POST", "/refund", payload, d -> new RefundResult(d.path("id").asLong(), text(d, "status"),
                d.path("amount").asLong(), text(d, "currency"), text(d.path("transaction"), "reference"), text(d, "merchant_note")), false);
    }

    /** {@code GET /transaction}: one page of transactions (reconciliation). */
    public PaystackResult<TransactionPage> listTransactions(BillingEnvironment env, TransactionQuery q) {
        return list(env, q, true, "list");
    }

    /**
     * The cheap authenticated call of the credentials {@code verify} (a transaction list with {@code perPage=1}). A
     * rejected key is returned as {@link PaystackResult.KeyRejected} and NOT recorded by the client: the verify use case
     * records it itself, with the admin as the actor.
     */
    public PaystackResult<TransactionPage> ping(BillingEnvironment env) {
        return list(env, new TransactionQuery(null, null, null, 1, 1), false, "ping");
    }

    private PaystackResult<TransactionPage> list(BillingEnvironment env, TransactionQuery q, boolean recordRejection, String op) {
        List<String> params = new ArrayList<>();
        params.add("perPage=" + Math.min(Math.max(q.perPage(), 1), 100));
        params.add("page=" + Math.max(q.page(), 1));
        if (q.status() != null) {
            params.add("status=" + enc(q.status()));
        }
        if (q.from() != null) {
            params.add("from=" + enc(DateTimeFormatter.ISO_INSTANT.format(q.from().truncatedTo(ChronoUnit.SECONDS))));
        }
        if (q.to() != null) {
            params.add("to=" + enc(DateTimeFormatter.ISO_INSTANT.format(q.to().truncatedTo(ChronoUnit.SECONDS))));
        }
        return callRaw(env, op, recordRejection, "GET", "/transaction?" + String.join("&", params), null, root -> {
            List<Transaction> items = new ArrayList<>();
            for (JsonNode n : root.path("data")) {
                Transaction t = transaction(n);
                if (t != null) {
                    items.add(t);
                }
            }
            JsonNode meta = root.path("meta");
            return new TransactionPage(items, meta.path("page").asInt(q.page()), meta.path("perPage").asInt(q.perPage()),
                    meta.path("total").asLong(items.size()));
        }, false);
    }

    // ------------------------------------------------------------------------------------------ the one call path

    private interface Mapper<T> {
        T map(JsonNode node);
    }

    /** Calls and hands the mapper the {@code data} node. */
    private <T> PaystackResult<T> call(BillingEnvironment env, String op, boolean recordRejection, String method, String path,
                                       JsonNode payload, Mapper<T> mapper, boolean checkoutUrl) {
        return callRaw(env, op, recordRejection, method, path, payload, root -> mapper.map(root.path("data")), checkoutUrl);
    }

    private <T> PaystackResult<T> callRaw(BillingEnvironment env, String op, boolean recordRejection, String method, String path,
                                          JsonNode payload, Mapper<T> mapper, boolean checkoutUrl) {
        Optional<String> secretKey = credentials.secretKey(env);
        if (secretKey.isEmpty()) {
            return outcome(op, env, new PaystackResult.NotConfigured<>());
        }
        CircuitBreaker breaker = breakers.get(env);
        if (!bulkhead.tryAcquire()) {
            return outcome(op, env, new PaystackResult.Unavailable<>(Reason.BULKHEAD_FULL)); // fail fast, nothing sent
        }
        if (!breaker.tryAcquire()) {
            bulkhead.release();
            return outcome(op, env, new PaystackResult.Unavailable<>(Reason.CIRCUIT_OPEN));
        }
        try {
            HttpRequest request = buildRequest(env, method, path, payload, secretKey.get());
            HttpResponse<InputStream> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (HttpConnectTimeoutException | ConnectException e) {
                breaker.failure();
                return outcome(op, env, new PaystackResult.Unavailable<>(Reason.CONNECT_FAILED));
            } catch (IOException e) {
                breaker.failure(); // sent (or maybe sent) and no answer: timeout, reset, TLS failure after connect
                return outcome(op, env, new PaystackResult.UnknownOutcome<>());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                breaker.failure();
                return outcome(op, env, new PaystackResult.UnknownOutcome<>());
            }
            int status = response.statusCode();
            JsonNode root = readJson(response);
            if (status == 401 || status == 403) {
                breaker.success();
                if (recordRejection) {
                    credentials.keyRejected(env);
                }
                return outcome(op, env, new PaystackResult.KeyRejected<>());
            }
            if (status == 429) {
                breaker.failure();
                return outcome(op, env, new PaystackResult.Unavailable<>(Reason.RATE_LIMITED));
            }
            if (status >= 500) {
                breaker.failure();
                return outcome(op, env, new PaystackResult.Unavailable<>(Reason.SERVER_ERROR));
            }
            breaker.success();
            if (status >= 400) {
                return outcome(op, env, new PaystackResult.Rejected<>(status));
            }
            if (root == null) {
                return outcome(op, env, new PaystackResult.UnknownOutcome<>()); // 2xx without usable JSON
            }
            if (!root.path("status").asBoolean(false)) {
                return outcome(op, env, new PaystackResult.Rejected<>(status));
            }
            T value = mapper.map(root);
            if (value == null) {
                return outcome(op, env, new PaystackResult.UnknownOutcome<>());
            }
            if (checkoutUrl && value instanceof InitializeResult init && !init.authorizationUrl().startsWith(props.checkoutUrlPrefix())) {
                log.error("Paystack returned a checkout URL outside the allowed host ({}, {})", op, env);
                return outcome(op, env, new PaystackResult.Unavailable<>(Reason.BAD_CHECKOUT_URL));
            }
            return outcome(op, env, new PaystackResult.Ok<>(value));
        } finally {
            bulkhead.release();
        }
    }

    private HttpRequest buildRequest(BillingEnvironment env, String method, String path, JsonNode payload, String secretKey) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(props.baseUrl(env) + path))
                    .timeout(Duration.ofMillis(props.readTimeoutMillis()))
                    .header("Authorization", "Bearer " + secretKey)
                    .header("Accept", "application/json");
            if (payload == null) {
                return b.GET().build();
            }
            return b.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload))).build();
        } catch (RuntimeException | IOException e) {
            // never chain the cause: a builder message could quote the header value
            throw new IllegalStateException("The Paystack request could not be built");
        }
    }

    private static JsonNode readJson(HttpResponse<InputStream> response) {
        try (InputStream in = response.body()) {
            byte[] bytes = in.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                return null;
            }
            JsonNode node = JSON.readTree(bytes);
            return node != null && node.isObject() ? node : null;
        } catch (IOException e) {
            return null;
        }
    }

    private <T> PaystackResult<T> outcome(String op, BillingEnvironment env, PaystackResult<T> result) {
        meters.counter("billing.paystack.calls", "op", op, "env", env.name(), "outcome", result.getClass().getSimpleName()).increment();
        if (!(result instanceof PaystackResult.Ok)) {
            log.warn("Paystack call {} ({}) ended as {}", op, env, result.getClass().getSimpleName());
        }
        return result;
    }

    // ------------------------------------------------------------------------------------------ mapping helpers

    private static void requireReference(String reference) {
        if (reference == null || !REFERENCE.matcher(reference).matches()) {
            throw new IllegalArgumentException("invalid Paystack reference");
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Transaction transaction(JsonNode d) {
        if (d == null || !d.isObject() || !d.hasNonNull("reference")) {
            return null;
        }
        JsonNode a = d.path("authorization");
        Authorization auth = a.hasNonNull("authorization_code")
                ? new Authorization(text(a, "authorization_code"), text(a, "signature"), text(a, "brand"), text(a, "last4"),
                text(a, "exp_month"), text(a, "exp_year"), text(a, "bank"), text(a, "channel"), a.path("reusable").asBoolean(false))
                : null;
        Instant paidAt = null;
        if (d.hasNonNull("paid_at")) {
            try {
                paidAt = Instant.parse(d.get("paid_at").asText());
            } catch (RuntimeException e) {
                paidAt = null;
            }
        }
        JsonNode c = d.path("customer");
        return new Transaction(d.path("id").asLong(), text(d, "reference"), text(d, "status"), d.path("amount").asLong(),
                text(d, "currency"), text(d, "channel"), paidAt, auth, text(c, "email"), text(c, "customer_code"));
    }
}
