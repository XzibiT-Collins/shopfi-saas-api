package com.example.perfume_budget.contract;

import com.example.perfume_budget.contract.OnboardingDocExamples.Example;
import com.example.perfume_budget.events.UserRegistrationEvent;
import com.example.perfume_budget.notification.EmailService;
import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import com.example.perfume_budget.support.PostgresTestDatabase;
import com.example.perfume_budget.tenancy.ProvisioningRequest;
import com.example.perfume_budget.tenancy.TenantProvisioningException;
import com.example.perfume_budget.tenancy.TenantProvisioningService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * PROV-03 contract test. Drives the real onboarding controller through MockMvc on PostgreSQL and checks it against
 * the examples in docs/api/onboarding.md and the schema docs/api/onboarding.schema.json:
 * valid request examples succeed, invalid ones fail with the documented status, every documented response example
 * equals what the server returns for its scenario (and validates against the schema), and the generated OpenAPI
 * output for the controller (docs/api/openapi-onboarding.json) is up to date.
 *
 * <p>Regenerate the OpenAPI file with {@code -Dopenapi.regenerate=true} on the mvn command line.
 */
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS) // frees the pool (shared DB connection-slot limit)
@Import(OnboardingContractTest.OtpCapture.class)
@TestPropertySource(properties = {
        "tenant.base-domain=shopfi.test",
        "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=0",
        "platform.cors.allowed-origins=https://wizard.shopfi.test",
        // generous limits so the scenarios do not trip them; resend-email stays at its default (3) on purpose
        "onboarding.signup-ip.capacity=1000",
        "onboarding.signup-email.capacity=1000",
        "onboarding.verify-ip.capacity=1000",
        "onboarding.verify-email.capacity=1000",
        "onboarding.status-ip.capacity=100000",
        "onboarding.resend-ip.capacity=1000"
})
class OnboardingContractTest extends AbstractPostgresIntegrationTest {

    private static final String BASE = "/api/v1/platform/onboarding";
    private static final ObjectMapper MAPPER = OnboardingDocExamples.MAPPER;
    private static final String DOC_SLUG = "acme-store";

    @TestConfiguration
    static class OtpCapture {
        static final Map<String, String> OTPS = new ConcurrentHashMap<>();

        @Bean
        OtpListener contractOtpListener() {
            return new OtpListener();
        }

        static class OtpListener {
            @EventListener
            void on(UserRegistrationEvent e) {
                OTPS.put(e.email(), e.otp());
            }
        }
    }

    @MockitoBean EmailService emailService;
    @MockitoSpyBean TenantProvisioningService provisioning;
    @Autowired MockMvc mvc;

    private final OnboardingDocExamples docs = new OnboardingDocExamples();

    // ------------------------------------------------------------------------------------------ helpers

    private MvcResult postJson(String path, String json, String ip) throws Exception {
        return mvc.perform(post(BASE + path).contentType("application/json").content(json)
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                })).andReturn();
    }

    private MvcResult getStatus(String slug, String ip) throws Exception {
        return mvc.perform(get(BASE + "/status").param("slug", slug).with(r -> {
            r.setRemoteAddr(ip);
            return r;
        })).andReturn();
    }

    private static JsonNode body(MvcResult r) throws Exception {
        return MAPPER.readTree(r.getResponse().getContentAsString());
    }

    private ObjectNode payload(String exampleName) {
        return (ObjectNode) docs.get(exampleName).json().deepCopy();
    }

    private static String otpFor(String email) {
        String otp = OtpCapture.OTPS.get(email);
        assertNotNull(otp, "no OTP captured for " + email);
        assertTrue(otp.matches("\\d{6}"), "OTP is 6 digits");
        return otp;
    }

    private static void sql(String sql) {
        try (Connection c = PostgresTestDatabase.connect(); var st = c.createStatement()) {
            st.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private String waitForStatus(String slug, String wanted) throws Exception {
        long deadline = System.currentTimeMillis() + 90_000;
        String last = "";
        while (System.currentTimeMillis() < deadline) {
            MvcResult r = getStatus(slug, "198.51.100.77");
            last = r.getResponse().getContentAsString();
            if (r.getResponse().getStatus() == 200 && body(r).get("data").get("status").asText().equals(wanted)) {
                return last;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("status never reached " + wanted + "; last=" + last);
    }

    private final Map<String, MvcResult> actual = new LinkedHashMap<>();
    private final Map<String, String> slugOf = new HashMap<>();

    private void record(String exampleName, MvcResult r, String scenarioSlug) {
        assertNull(actual.put(exampleName, r), "scenario recorded twice: " + exampleName);
        slugOf.put(exampleName, scenarioSlug);
    }

    /** The doc example and the live response must agree on status, schema, description and data. */
    private void assertMatchesDoc(String name, MvcResult r, String scenarioSlug) throws Exception {
        Example ex = docs.get(name);
        assertEquals(ex.status(), r.getResponse().getStatus(), name + " status: " + r.getResponse().getContentAsString());
        String raw = r.getResponse().getContentAsString().replace(scenarioSlug, DOC_SLUG);
        JsonNode live = MAPPER.readTree(raw);
        assertTrue(docs.validate(ex.schema(), live).isEmpty(), name + " violates " + ex.schema() + ": " + docs.validate(ex.schema(), live) + " body=" + raw);
        assertEquals(ex.json().get("description"), live.get("description"), name + " description");
        if (name.equals("signup-validation-error")) {
            // field names are contract; the English messages come from the validator and the JVM locale
            assertEquals(fieldNames(ex.json().get("data")), fieldNames(live.get("data")), name + " fields");
        } else {
            assertEquals(ex.json().get("data"), live.get("data"), name + " data");
        }
        assertTrue(r.getResponse().getContentType().startsWith("application/json"), name + " content type");
    }

    private static Set<String> fieldNames(JsonNode n) {
        Set<String> s = new TreeSet<>();
        n.fieldNames().forEachRemaining(s::add);
        return s;
    }

    // ------------------------------------------------------------------------------------------ the scenarios

    @Test
    void everyDocumentedResponseExampleEqualsWhatTheServerReturns() throws Exception {
        String ip = "203.0.113.10";
        CountDownLatch release = new CountDownLatch(1);
        Mockito.doAnswer(inv -> {
            ProvisioningRequest req = inv.getArgument(0);
            if (DOC_SLUG.equals(req.slug())) {
                // hold the saga so PROVISIONING is observable deterministically
                assertTrue(release.await(60, TimeUnit.SECONDS));
            }
            if ("fail-shop".equals(req.slug())) {
                throw new TenantProvisioningException("fail-shop", true, new RuntimeException("boom"));
            }
            return inv.callRealMethod();
        }).when(provisioning).provision(org.mockito.ArgumentMatchers.any(ProvisioningRequest.class));
        try {
            // ---- signup (valid example payload, verbatim) ----
            ObjectNode signup = payload("signup-request");
            MvcResult created = postJson("/signup", signup.toString(), ip);
            record("signup-created", created, DOC_SLUG);
            String email = signup.get("ownerEmail").asText();
            String password = signup.get("password").asText();
            assertFalse(created.getResponse().getContentAsString().contains(password), "password never echoed");
            assertFalse(created.getResponse().getContentAsString().contains(email), "e-mail never echoed");
            record("status-pending", getStatus(DOC_SLUG, ip), DOC_SLUG);

            // ---- 409 on duplicate (same payload) ----
            record("signup-conflict", postJson("/signup", signup.toString(), ip), DOC_SLUG);

            // ---- wrong code, then the right one ----
            String otp = otpFor(email);
            String wrong = otp.equals("000000") ? "111111" : "000000";
            ObjectNode verify = payload("verify-request");
            verify.put("otp", wrong);
            record("verify-invalid-code", postJson("/verify", verify.toString(), ip), DOC_SLUG);

            verify.put("otp", otp);
            MvcResult accepted = postJson("/verify", verify.toString(), ip);
            record("verify-accepted", accepted, DOC_SLUG);

            // verified PENDING reports as PROVISIONING while the saga is held
            record("status-provisioning", getStatus(DOC_SLUG, ip), DOC_SLUG);

            // the code is single use: replay is the same generic 400, provisioning is not started twice
            MvcResult replay = postJson("/verify", verify.toString(), ip);
            assertEquals(400, replay.getResponse().getStatus());
            assertEquals("Invalid or expired verification code", body(replay).get("description").asText());

            // resend: identical 202 body for in-flight, unknown slug and wrong e-mail (enumeration-safe)
            ObjectNode resend = payload("resend-request");
            MvcResult inFlight = postJson("/resend", resend.toString(), ip);
            record("resend-accepted", inFlight, DOC_SLUG);
            ObjectNode unknown = resend.deepCopy().put("slug", "nobody-here");
            ObjectNode wrongMail = resend.deepCopy().put("email", "someone-else@acme-store.example");
            String expectedBody = inFlight.getResponse().getContentAsString();
            for (ObjectNode other : List.of(unknown, wrongMail)) {
                MvcResult r = postJson("/resend", other.toString(), ip);
                assertEquals(202, r.getResponse().getStatus());
                assertEquals(expectedBody, r.getResponse().getContentAsString(), "resend must not reveal whether a signup exists");
            }
            assertEquals(otp, OtpCapture.OTPS.get(email), "resend while provisioning must not issue a new code");

            release.countDown();
            String done = waitForStatus(DOC_SLUG, "ACTIVE");
            MvcResult active = getStatus(DOC_SLUG, ip);
            assertTrue(done.contains("ACTIVE"));
            record("status-active", active, DOC_SLUG);
            // status is case and whitespace insensitive
            assertEquals(active.getResponse().getContentAsString(), getStatus("  ACME-Store ", ip).getResponse().getContentAsString());

            // ---- unknown / malformed slug, missing param ----
            record("status-unknown", getStatus("no-such-shop", ip), "no-such-shop");
            assertEquals(404, getStatus("A", ip).getResponse().getStatus(), "malformed slug is 404, not 400");
            MvcResult noParam = mvc.perform(get(BASE + "/status")).andReturn();
            assertEquals(400, noParam.getResponse().getStatus());
            assertEquals("Missing required parameter: slug.", body(noParam).get("description").asText());

            // ---- 400 variants ----
            record("signup-validation-error", postJson("/signup", docs.get("signup-email-malformed").json().toString(), ip), "bad-mail");
            record("signup-slug-reserved-error", postJson("/signup", docs.get("signup-slug-reserved").json().toString(), ip), "admin");
            record("signup-malformed-body-error", postJson("/signup", "{", ip), "none");

            // ---- lockout: 5 wrong codes then even the right code is 429 + Retry-After ----
            ObjectNode bravo = payload("signup-request-minimal");
            assertEquals(201, postJson("/signup", bravo.toString(), ip).getResponse().getStatus());
            String bravoEmail = bravo.get("ownerEmail").asText();
            String bravoOtp = otpFor(bravoEmail);
            String bravoWrong = bravoOtp.equals("000000") ? "111111" : "000000";
            for (int i = 0; i < 5; i++) {
                MvcResult w = postJson("/verify", "{\"slug\":\"bravo-goods\",\"email\":\"" + bravoEmail + "\",\"otp\":\"" + bravoWrong + "\"}", ip);
                assertEquals(400, w.getResponse().getStatus(), "attempt " + (i + 1));
            }
            MvcResult locked = postJson("/verify", "{\"slug\":\"bravo-goods\",\"email\":\"" + bravoEmail + "\",\"otp\":\"" + bravoOtp + "\"}", ip);
            record("verify-locked", locked, "bravo-goods");
            long retry = Long.parseLong(Objects.requireNonNull(locked.getResponse().getHeader("Retry-After")));
            assertTrue(retry >= 1 && retry <= 15 * 60 + 1, "Retry-After is the remaining lock, <= 15 min: " + retry);
            // resend does not unlock and issues nothing while locked
            sql("UPDATE master.tenant_signups SET last_otp_sent_at = now() - interval '1 hour' WHERE tenant_id = (SELECT id FROM master.tenants WHERE slug='bravo-goods')");
            assertEquals(202, postJson("/resend", "{\"slug\":\"bravo-goods\",\"email\":\"" + bravoEmail + "\"}", ip).getResponse().getStatus());
            assertEquals(bravoOtp, OtpCapture.OTPS.get(bravoEmail), "no new code while locked");
            assertEquals(429, postJson("/verify", "{\"slug\":\"bravo-goods\",\"email\":\"" + bravoEmail + "\",\"otp\":\"" + bravoOtp + "\"}", ip).getResponse().getStatus());

            // ---- FAILED (saga failure simulated the same way as TenantOnboardingIntegrationTest) ----
            ObjectNode fail = payload("signup-request");
            fail.put("slug", "fail-shop").put("ownerEmail", "owner@fail-shop.example");
            assertEquals(201, postJson("/signup", fail.toString(), ip).getResponse().getStatus());
            sql("UPDATE master.tenants SET status='FAILED' WHERE slug='fail-shop'");
            String failOtp = otpFor("owner@fail-shop.example");
            assertEquals(202, postJson("/verify", "{\"slug\":\"fail-shop\",\"email\":\"owner@fail-shop.example\",\"otp\":\"" + failOtp + "\"}", ip).getResponse().getStatus());
            Thread.sleep(500);
            record("status-failed", getStatus("fail-shop", ip), "fail-shop");
            // FAILED is retryable: old code is dead, resend (after the cooldown) issues a new one
            assertEquals(400, postJson("/verify", "{\"slug\":\"fail-shop\",\"email\":\"owner@fail-shop.example\",\"otp\":\"" + failOtp + "\"}", ip).getResponse().getStatus());
            sql("UPDATE master.tenant_signups SET last_otp_sent_at = now() - interval '1 hour' WHERE tenant_id = (SELECT id FROM master.tenants WHERE slug='fail-shop')");
            assertEquals(202, postJson("/resend", "{\"slug\":\"fail-shop\",\"email\":\"owner@fail-shop.example\"}", ip).getResponse().getStatus());
            assertNotEquals(failOtp, OtpCapture.OTPS.get("owner@fail-shop.example"), "FAILED owner gets a fresh code");
            // and a FAILED tenant cannot be re-signed-up
            assertEquals(409, postJson("/signup", fail.toString(), ip).getResponse().getStatus());

            // ---- rate limit: resend-email defaults to 3 per hour; the 4th is 429 with Retry-After ----
            String rlBody = "{\"slug\":\"rate-limit\",\"email\":\"limited@rate.example\"}";
            for (int i = 0; i < 3; i++) {
                assertEquals(202, postJson("/resend", rlBody, ip).getResponse().getStatus());
            }
            MvcResult limited = postJson("/resend", rlBody, ip);
            record("rate-limited", limited, "rate-limit");
            assertTrue(Long.parseLong(Objects.requireNonNull(limited.getResponse().getHeader("Retry-After"))) >= 1);
        } finally {
            release.countDown();
            Mockito.reset(provisioning);
        }

        // ---- every recorded scenario equals its doc example, and every doc response example has a scenario ----
        for (var e : actual.entrySet()) {
            assertMatchesDoc(e.getKey(), e.getValue(), slugOf.get(e.getKey()));
        }
        Set<String> documented = new TreeSet<>();
        docs.examples.values().stream().filter(e -> e.kind().equals("response")).forEach(e -> documented.add(e.name()));
        assertEquals(documented, new TreeSet<>(actual.keySet()),
                "doc response examples and exercised scenarios must be the same set");
    }

    // ------------------------------------------------------------------------------------------ request examples

    @Test
    void invalidRequestExamplesAreRejectedWithTheDocumentedStatus() throws Exception {
        int n = 0;
        for (Example ex : docs.examples.values()) {
            if (!ex.kind().equals("invalid")) {
                continue;
            }
            String endpoint = switch (ex.schema()) {
                case "TenantSignupRequest" -> "/signup";
                case "TenantVerifyRequest" -> "/verify";
                case "TenantResendRequest" -> "/resend";
                default -> throw new AssertionError("no endpoint for " + ex.schema());
            };
            MvcResult r = postJson(endpoint, ex.json().toString(), "203.0.113." + (20 + n++));
            assertEquals(ex.status(), r.getResponse().getStatus(), ex.name() + ": " + r.getResponse().getContentAsString());
            JsonNode b = body(r);
            assertTrue(docs.validate("ErrorEnvelope", b).isEmpty(), ex.name() + " error body shape: " + b);
            assertFalse(b.get("description").asText().isBlank(), ex.name());
        }
        assertTrue(n >= 8);
    }

    @Test
    void slugAndPasswordRulesInTheDocMatchTheServer() throws Exception {
        String ip = "203.0.113.60";
        // reserved names listed in the schema (and so in the doc) are all rejected
        List<String> reserved = new ArrayList<>();
        docs.schemaDoc.get("$defs").get("Slug").get("not").get("anyOf").get(0).get("enum").forEach(x -> reserved.add(x.asText()));
        reserved.add("pg-catalog");
        reserved.add("tenant-foo");
        reserved.add("ADMIN"); // normalised before the check
        for (String slug : reserved) {
            ObjectNode p = payload("signup-request").put("slug", slug);
            MvcResult r = postJson("/signup", p.toString(), ip);
            assertEquals(400, r.getResponse().getStatus(), slug);
            assertEquals("slug is reserved", body(r).get("description").asText(), slug);
        }
        // length boundaries: 3 and 40 accepted, 2 and 41 rejected
        String s40 = "a".repeat(40);
        for (String[] c : new String[][]{{"abc", "201"}, {s40, "201"}, {"ab", "400"}, {"a".repeat(41), "400"}, {"-abc", "400"}, {"abc-", "400"}}) {
            ObjectNode p = payload("signup-request").put("slug", c[0]).put("ownerEmail", "o-" + c[0].length() + c[0].charAt(0) + "@slug.example");
            assertEquals(Integer.parseInt(c[1]), postJson("/signup", p.toString(), ip).getResponse().getStatus(), c[0]);
        }
        // password: trimmed, >= 8 chars, <= 72 bytes
        String[][] pw = {{"1234567", "400"}, {"   1234567   ", "400"}, {"12345678", "201"}, {"x".repeat(72), "201"},
                {"x".repeat(73), "400"}, {"é".repeat(37), "400"} /* 74 bytes */, {"é".repeat(36), "201"}};
        int i = 0;
        for (String[] c : pw) {
            ObjectNode p = payload("signup-request").put("slug", "pw-case-" + i).put("ownerEmail", "pw" + i + "@pw.example").put("password", c[0]);
            MvcResult r = postJson("/signup", p.toString(), ip);
            assertEquals(Integer.parseInt(c[1]), r.getResponse().getStatus(), "password case " + i + ": " + r.getResponse().getContentAsString());
            i++;
        }
        // schema agrees with the server on these boundaries
        assertFalse(docs.validate("TenantSignupRequest", payload("signup-request").put("slug", "ab")).isEmpty());
        assertTrue(docs.validate("TenantSignupRequest", payload("signup-request").put("slug", s40)).isEmpty());
        assertFalse(docs.validate("TenantSignupRequest", payload("signup-request").put("slug", "a".repeat(41))).isEmpty());
    }

    @Test
    void validExamplesAreAcceptedAndSchemaValid() throws Exception {
        for (Example ex : docs.examples.values()) {
            if (ex.kind().equals("valid")) {
                assertTrue(docs.validate(ex.schema(), ex.json()).isEmpty(), ex.name());
            }
        }
        // signup-request-minimal (no plan) defaults to FREE and is accepted
        ObjectNode minimal = payload("signup-request-minimal").put("slug", "minimal-plan").put("ownerEmail", "m@minimal.example");
        assertFalse(minimal.has("plan"));
        assertEquals(201, postJson("/signup", minimal.toString(), "203.0.113.70").getResponse().getStatus());
    }

    // ------------------------------------------------------------------------------------------ platform scope / CORS

    @Test
    void onboardingNeedsNoTenantOrigin_andCorsPreflightFollowsTheConfiguredAllowList() throws Exception {
        // no Origin header at all: a platform path needs no tenant signal (the 404 below is the controller's own)
        MvcResult noOrigin = getStatus("no-such-shop", "203.0.113.80");
        assertEquals(404, noOrigin.getResponse().getStatus());
        assertEquals("Unknown tenant", body(noOrigin).get("description").asText());
        // an allowed Origin that is not a tenant host (the wizard host) is not a tenant error either
        MvcResult wizardOrigin = mvc.perform(get(BASE + "/status").param("slug", DOC_SLUG + "-none")
                .header("Origin", "https://wizard.shopfi.test")).andReturn();
        assertEquals(404, wizardOrigin.getResponse().getStatus());
        assertEquals("Unknown tenant", body(wizardOrigin).get("description").asText());
        // an Origin outside platform.cors.allowed-origins is rejected by the CORS layer itself (403, plain text, not the envelope)
        MvcResult blocked = mvc.perform(get(BASE + "/status").param("slug", "no-such-shop")
                .header("Origin", "https://unknown.example")).andReturn();
        assertEquals(403, blocked.getResponse().getStatus());
        assertEquals("Invalid CORS request", blocked.getResponse().getContentAsString());

        // configured origin passes preflight; an unlisted one does not; Retry-After is exposed (DOMAIN-01)
        MvcResult ok = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options(BASE + "/signup")
                .header("Origin", "https://wizard.shopfi.test").header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type")).andReturn();
        assertEquals(200, ok.getResponse().getStatus());
        assertEquals("https://wizard.shopfi.test", ok.getResponse().getHeader("Access-Control-Allow-Origin"));
        MvcResult bad = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options(BASE + "/signup")
                .header("Origin", "https://evil.example").header("Access-Control-Request-Method", "POST")).andReturn();
        assertEquals(403, bad.getResponse().getStatus());
        MvcResult actualCors = mvc.perform(get(BASE + "/status").param("slug", "no-such-shop")
                .header("Origin", "https://wizard.shopfi.test")).andReturn();
        String exposed = actualCors.getResponse().getHeader("Access-Control-Expose-Headers");
        assertNotNull(exposed, "doc section 11 says Retry-After and ETag are exposed; update the doc if this changes");
        assertTrue(exposed.contains("Retry-After") && exposed.contains("ETag"), exposed);
    }

    // ------------------------------------------------------------------------------------------ OpenAPI (AC3)

    @Test
    void generatedOpenApiForTheOnboardingControllerIsCommittedAndUpToDate() throws Exception {
        MvcResult r = mvc.perform(get("/v3/api-docs")).andReturn();
        assertEquals(200, r.getResponse().getStatus(), "springdoc should serve /v3/api-docs");
        ObjectNode full = (ObjectNode) MAPPER.readTree(r.getResponse().getContentAsString());
        ObjectNode filtered = filterToOnboarding(full);

        ObjectMapper sorted = new ObjectMapper()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).enable(SerializationFeature.INDENT_OUTPUT);
        sorted.configure(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
        Object tree = sorted.treeToValue(filtered, Object.class);
        String generated = sorted.writeValueAsString(tree) + "\n";

        // content checks independent of the committed file
        JsonNode paths = filtered.get("paths");
        assertEquals(Set.of("/api/v1/platform/onboarding/signup", "/api/v1/platform/onboarding/verify",
                "/api/v1/platform/onboarding/resend", "/api/v1/platform/onboarding/status"), fieldNames(paths));
        assertTrue(paths.get(BASE + "/signup").get("post").get("responses").has("201"));
        assertTrue(paths.get(BASE + "/signup").get("post").get("responses").has("409"));
        assertTrue(paths.get(BASE + "/verify").get("post").get("responses").has("202"));
        assertTrue(paths.get(BASE + "/resend").get("post").get("responses").has("202"));
        assertTrue(paths.get(BASE + "/status").get("get").get("responses").has("404"));
        assertTrue(paths.get(BASE + "/status").get("get").get("responses").has("429"));
        JsonNode schemas = filtered.get("components").get("schemas");
        for (String s : List.of("TenantSignupRequest", "TenantVerifyRequest", "TenantResendRequest")) {
            assertTrue(schemas.has(s), s);
        }
        assertFalse(generated.contains("password\" : \"") , "no example secrets");

        java.nio.file.Path file = OnboardingDocExamples.DOCS.resolve("openapi-onboarding.json");
        if (Boolean.getBoolean("openapi.regenerate")) {
            Files.writeString(file, generated, StandardCharsets.UTF_8);
        }
        assertTrue(Files.exists(file), "docs/api/openapi-onboarding.json is missing: run the contract test once with "
                + "-Dopenapi.regenerate=true and commit the file (it is never recreated silently)");
        assertEquals(Files.readString(file, StandardCharsets.UTF_8), generated,
                "docs/api/openapi-onboarding.json is stale: run the contract test with -Dopenapi.regenerate=true and commit it");
    }

    /** Keeps only the onboarding paths and the component schemas they (transitively) reference. */
    private static ObjectNode filterToOnboarding(ObjectNode full) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("openapi", full.get("openapi").asText());
        ObjectNode info = out.putObject("info");
        info.put("title", "Shopfi tenant onboarding API");
        info.put("version", "v1");
        info.put("description", "Generated by springdoc from TenantOnboardingController (PROV-03). Contract: docs/api/onboarding.md.");
        ObjectNode paths = out.putObject("paths");
        full.get("paths").fields().forEachRemaining(e -> {
            if (e.getKey().startsWith(BASE + "/")) {
                paths.set(e.getKey(), e.getValue());
            }
        });
        ArrayNode tags = out.putArray("tags");
        full.path("tags").forEach(t -> {
            if ("Tenant onboarding".equals(t.path("name").asText())) {
                tags.add(t);
            }
        });
        Set<String> needed = new TreeSet<>();
        collectRefs(paths, needed);
        ObjectNode all = (ObjectNode) full.get("components").get("schemas");
        ObjectNode schemas = MAPPER.createObjectNode();
        Deque<String> todo = new ArrayDeque<>(needed);
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            String name = todo.pop();
            if (!seen.add(name) || !all.has(name)) {
                continue;
            }
            schemas.set(name, all.get(name));
            Set<String> inner = new TreeSet<>();
            collectRefs(all.get(name), inner);
            todo.addAll(inner);
        }
        out.putObject("components").set("schemas", schemas);
        return out;
    }

    private static void collectRefs(JsonNode n, Set<String> into) {
        if (n.isObject()) {
            JsonNode ref = n.get("$ref");
            if (ref != null && ref.asText().startsWith("#/components/schemas/")) {
                into.add(ref.asText().substring("#/components/schemas/".length()));
            }
            n.elements().forEachRemaining(c -> collectRefs(c, into));
        } else if (n.isArray()) {
            n.elements().forEachRemaining(c -> collectRefs(c, into));
        }
    }
}
