package com.example.perfume_budget.integration;

import com.example.perfume_budget.notification.MailMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The test-scope side server of the real-stack launcher, a plain JDK HttpServer on its own port (it is NOT part of the
 * Spring application: no controller, no security rule and no route exists in main code or on the API port).
 *
 * <pre>
 *   Paystack stub (the backend's paystack.url points at {base}/paystack):
 *     POST /paystack/transaction/initialize     -> {status, message, data:{authorization_url, access_code, reference}}
 *     GET  /paystack/transaction/verify/{ref}   -> {status, message, data:{status, reference, amount, currency, channel}}
 *     GET  /ac{ref}                            -> the "hosted payment page" (links back to the tenant callback)
 *   Test controls (never reachable on the API port):
 *     GET  /__test/health
 *     GET  /__test/mail?to=&amp;subject=           -> captured e-mails, newest last
 *     GET  /__test/otp?to=                      -> {code} newest 6 digit code mailed to the address (404 when none)
 *     GET  /__test/reset-token?to=              -> {token} newest password reset token mailed to the address
 *     GET  /__test/paystack/initiations         -> every initialize request received (reference, amount, email, callback)
 *     POST /__test/paystack/outcome?reference=&amp;status=success|failed|abandoned   -> what verify answers for the reference
 *     GET  /__test/tenants/{slug}/gateway       -> {webhookToken, secret}: what a test needs to sign a webhook
 * </pre>
 */
final class ControlServer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SIX_DIGITS = Pattern.compile(">\\s*(\\d{6})\\s*<");
    private static final Pattern RESET_TOKEN = Pattern.compile("resetPasswordToken=([A-Za-z0-9_-]+)");

    private final HttpServer server;
    private final CapturingMailTransport mail;
    private final JdbcTemplate jdbc;
    private final int frontendPort;
    private final String payOrigin;
    private final Map<String, ObjectNode> initiations = new ConcurrentHashMap<>();
    private final List<String> initiationOrder = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<String, String> outcomes = new ConcurrentHashMap<>();

    ControlServer(int port, int frontendPort, CapturingMailTransport mail, JdbcTemplate jdbc) throws IOException {
        this.frontendPort = frontendPort;
        this.mail = mail;
        this.jdbc = jdbc;
        this.payOrigin = "http://127.0.0.1:" + port;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        this.server.setExecutor(Executors.newFixedThreadPool(4));
        this.server.createContext("/", this::route);
    }

    void start() {
        server.start();
    }

    private void route(HttpExchange ex) throws IOException {
        try {
            URI uri = ex.getRequestURI();
            String path = uri.getPath();
            String method = ex.getRequestMethod();
            Map<String, String> q = query(uri.getRawQuery());
            if (path.equals("/__test/health")) {
                json(ex, 200, JSON.createObjectNode().put("ok", true));
            } else if (path.equals("/__test/mail")) {
                ArrayNode out = JSON.createArrayNode();
                for (MailMessage m : mail.sent) {
                    if (q.containsKey("to") && !q.get("to").equalsIgnoreCase(m.to())) continue;
                    if (q.containsKey("subject") && (m.subject() == null || !m.subject().contains(q.get("subject")))) continue;
                    out.addObject().put("to", m.to()).put("subject", m.subject()).put("html", m.html());
                }
                json(ex, 200, out);
            } else if (path.equals("/__test/otp")) {
                String code = newest(q.get("to"), SIX_DIGITS);
                if (code == null) json(ex, 404, JSON.createObjectNode().put("error", "no mail with a code for that address"));
                else json(ex, 200, JSON.createObjectNode().put("code", code));
            } else if (path.equals("/__test/reset-token")) {
                String token = newest(q.get("to"), RESET_TOKEN);
                if (token == null) json(ex, 404, JSON.createObjectNode().put("error", "no reset mail for that address"));
                else json(ex, 200, JSON.createObjectNode().put("token", token));
            } else if (path.equals("/__test/paystack/initiations")) {
                ArrayNode out = JSON.createArrayNode();
                for (String ref : initiationOrder) out.add(initiations.get(ref));
                json(ex, 200, out);
            } else if (path.equals("/__test/paystack/outcome") && method.equals("POST")) {
                outcomes.put(q.get("reference"), q.getOrDefault("status", "success"));
                json(ex, 200, JSON.createObjectNode().put("ok", true));
            } else if (path.matches("/__test/tenants/[a-z0-9-]+/gateway")) {
                String slug = path.split("/")[3];
                String token = jdbc.queryForObject("SELECT webhook_token FROM master.tenants WHERE slug = ?", String.class, slug);
                json(ex, 200, JSON.createObjectNode().put("webhookToken", token).put("secret", IntegrationSeeder.paystackSecret(slug)));
            } else if (path.equals("/paystack/transaction/initialize") && method.equals("POST")) {
                initialize(ex);
            } else if (path.startsWith("/paystack/transaction/verify/")) {
                verify(ex, URLDecoder.decode(path.substring("/paystack/transaction/verify/".length()), StandardCharsets.UTF_8));
            } else if (path.matches("/ac[A-Za-z0-9]+")) {
                // like checkout.paystack.com/<access_code>: ONE path segment (the frontend refuses anything else)
                payPage(ex, path.substring("/ac".length()));
            } else {
                json(ex, 404, JSON.createObjectNode().put("error", "not found"));
            }
        } catch (Exception e) {
            json(ex, 500, JSON.createObjectNode().put("error", e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private String newest(String to, Pattern p) {
        String found = null;
        for (MailMessage m : mail.sent) {
            if (to != null && !to.equalsIgnoreCase(m.to())) continue;
            Matcher mt = p.matcher(m.html() == null ? "" : m.html());
            if (mt.find()) found = mt.group(1);
        }
        return found;
    }

    private void initialize(HttpExchange ex) throws IOException {
        JsonNode body = JSON.readTree(ex.getRequestBody());
        String ref = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        ObjectNode rec = JSON.createObjectNode();
        rec.put("reference", ref);
        rec.put("email", body.path("email").asText(null));
        rec.put("amount", body.path("amount").asText(null));
        rec.put("currency", body.path("currency").asText(null));
        rec.put("callback_url", body.path("callback_url").asText(null));
        rec.put("authorization", ex.getRequestHeaders().getFirst("Authorization"));
        initiations.put(ref, rec);
        initiationOrder.add(ref);
        ObjectNode out = JSON.createObjectNode().put("status", true).put("message", "Authorization URL created");
        out.putObject("data").put("authorization_url", payOrigin + "/ac" + ref).put("access_code", "ac" + ref).put("reference", ref);
        json(ex, 200, out);
    }

    private void verify(HttpExchange ex, String ref) throws IOException {
        ObjectNode rec = initiations.get(ref);
        if (rec == null) {
            json(ex, 404, JSON.createObjectNode().put("status", false).put("message", "Transaction reference not found"));
            return;
        }
        ObjectNode out = JSON.createObjectNode().put("status", true).put("message", "Verification successful");
        out.putObject("data").put("status", outcomes.getOrDefault(ref, "abandoned")).put("reference", ref)
                .put("amount", new java.math.BigDecimal(rec.get("amount").asText()).toPlainString())
                .put("currency", rec.get("currency").asText("GHS")).put("channel", "card").put("gateway_response", "Successful");
        json(ex, 200, out);
    }

    /**
     * The hosted payment page. The backend builds callback_url without a port (scheme://slug.base-domain/path), so
     * the stub points it at the frontend's real port; that is the only rewrite it does.
     */
    private void payPage(HttpExchange ex, String ref) throws IOException {
        ObjectNode rec = initiations.get(ref);
        String cb = rec == null ? "" : rec.path("callback_url").asText("");
        try {
            URI u = URI.create(cb);
            cb = u.getScheme() + "://" + u.getHost() + ":" + frontendPort + u.getPath();
        } catch (RuntimeException ignored) {
            // leave as is
        }
        String html = "<!doctype html><title>Paystack stub</title><h1 id=\"stub\">Paystack (stub)</h1>"
                + "<p id=\"ref\">" + ref + "</p><a id=\"return\" href=\"" + cb + "?reference=" + ref + "&trxref=" + ref + "\">Return to shop</a>";
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null) return m;
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            if (i < 0) continue;
            m.put(URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void json(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] b = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
