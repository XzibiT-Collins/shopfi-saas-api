package com.example.perfume_budget.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PROV-03 (AC2), no Spring and no database: the examples embedded in onboarding.md agree with
 * onboarding.schema.json. The server-side half of the contract is {@link OnboardingContractTest}.
 */
class OnboardingDocSchemaTest {

    private final OnboardingDocExamples docs = new OnboardingDocExamples();

    @Test
    void schemaIsValidDraft2020_12() {
        JsonNode schema = docs.schemaDoc;
        assertEquals("https://json-schema.org/draft/2020-12/schema", schema.get("$schema").asText());
        var meta = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(URI.create("https://json-schema.org/draft/2020-12/schema"));
        assertTrue(meta.validate(schema).isEmpty());
        for (String def : Set.of("TenantSignupRequest", "TenantVerifyRequest", "TenantResendRequest", "OnboardingStatus",
                "StatusEnvelope", "ResendEnvelope", "ErrorEnvelope")) {
            assertTrue(schema.get("$defs").has(def), "missing $defs/" + def);
        }
    }

    @Test
    void docContainsTheExpectedExamples() {
        assertTrue(docs.examples.keySet().containsAll(Set.of("signup-request", "signup-request-minimal", "verify-request",
                "resend-request", "signup-created", "verify-accepted", "resend-accepted", "status-pending",
                "status-provisioning", "status-active", "status-failed", "status-unknown", "signup-conflict",
                "verify-invalid-code", "verify-locked", "rate-limited")), docs.examples.keySet().toString());
        assertTrue(docs.examples.values().stream().filter(e -> e.kind().equals("invalid")).count() >= 8);
        docs.examples.values().forEach(e -> {
            assertTrue(docs.schemaDoc.get("$defs").has(e.schema()), e.name() + " names unknown schema " + e.schema());
            assertEquals(!e.kind().equals("valid"), e.status() != null, e.name() + ": status is required for invalid and response, forbidden for valid");
        });
    }

    @Test
    void everyValidExampleAndResponseExamplePassesItsSchema() {
        docs.examples.values().stream().filter(e -> !e.kind().equals("invalid")).forEach(e ->
                assertTrue(docs.validate(e.schema(), e.json()).isEmpty(), e.name() + ": " + docs.validate(e.schema(), e.json())));
    }

    @Test
    void everyInvalidExampleFailsItsSchema() {
        docs.examples.values().stream().filter(e -> e.kind().equals("invalid")).forEach(e ->
                assertFalse(docs.validate(e.schema(), e.json()).isEmpty(), e.name() + " should violate " + e.schema()));
    }

    @Test
    void schemaRejectsShapeDrift() {
        var ok = docs.get("status-active").json().deepCopy();
        assertTrue(docs.validate("StatusEnvelope", ok).isEmpty());

        var extraField = ok.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) extraField.get("data")).put("ownerEmail", "x@y.example");
        assertFalse(docs.validate("StatusEnvelope", extraField).isEmpty(), "extra data field (e-mail leak)");

        var badStatus = ok.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) badStatus.get("data")).put("status", "SUSPENDED");
        assertFalse(docs.validate("StatusEnvelope", badStatus).isEmpty(), "SUSPENDED is never reported");

        var urlsWhilePending = docs.get("status-pending").json().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) urlsWhilePending.get("data")).put("storefrontUrl", "https://a.example");
        assertFalse(docs.validate("StatusEnvelope", urlsWhilePending).isEmpty(), "URLs only when ACTIVE");

        var noData = docs.get("signup-created").json().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) noData).remove("data");
        assertFalse(docs.validate("StatusEnvelope", noData).isEmpty());

        var resendWrongText = docs.get("resend-accepted").json().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) resendWrongText).put("description", "Code sent");
        assertFalse(docs.validate("ResendEnvelope", resendWrongText).isEmpty(), "resend body must be the one fixed text");
    }
}
