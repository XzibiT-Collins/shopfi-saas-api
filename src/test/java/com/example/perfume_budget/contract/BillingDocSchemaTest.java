package com.example.perfume_budget.contract;

import com.example.perfume_budget.contract.BillingDocExamples.Example;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BILL-01 (billing.md section 21 item 13): every tagged example of billing.md against billing.schema.json, and the schema
 * itself. Pure (no server): the live half is {@link BillingDocContractTest}.
 */
class BillingDocSchemaTest {

    private static final BillingDocExamples DOCS = new BillingDocExamples();

    @Test
    void theSchemaIsAValidDraft202012Schema() {
        assertThat(DOCS.schemaDoc.get("$schema").asText()).isEqualTo("https://json-schema.org/draft/2020-12/schema");
        // compiling the whole document throws on an unresolved $ref or a malformed keyword
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(DOCS.schemaDoc);
        assertThat(DOCS.schemaDoc.get("$defs").size()).isGreaterThan(100);
    }

    @Test
    void theTagCountsAreComplete_everyTagIsParsed() {
        assertThat(DOCS.examples.size()).isGreaterThanOrEqualTo(180);
        assertThat(DOCS.fragments).containsOnlyKeys("tenant-billing-fields", "tenant-billing-fields-grace");
    }

    @Test
    void everyValidAndResponseExampleValidatesAndEveryInvalidOneFailsTheSchema() {
        List<String> problems = new ArrayList<>();
        for (Example e : DOCS.examples.values()) {
            assertThat(DOCS.schemaDoc.get("$defs").has(e.schema())).as(e.name() + " names an existing schema " + e.schema()).isTrue();
            var errors = DOCS.validate(e.schema(), e.json());
            if (e.kind().equals("invalid")) {
                if (errors.isEmpty()) {
                    problems.add(e.name() + " is tagged invalid but validates");
                }
                assertThat(e.status()).as(e.name()).isIn(400, 409);
            } else if (!errors.isEmpty()) {
                problems.add(e.name() + " violates " + e.schema() + ": " + errors.iterator().next().getMessage());
            }
            if (e.kind().equals("response") && e.status() == 200) {
                assertThat(e.json().get("description").isNull()).as(e.name() + " success description is null").isTrue();
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void everyFragmentValidatesAgainstItsSchema() {
        DOCS.fragments.values().forEach(f ->
                assertThat(DOCS.validate(f.schema(), f.json())).as(f.name()).isEmpty());
    }

    @Test
    void noExampleCarriesAnAuthorizationFieldOrAKey() {
        // billing.md 21 item 1 / S2: no example may show authorization_code / signature, and the only key-shaped values are the
        // documented non-keys of the credentials REQUEST examples
        for (Example e : DOCS.examples.values()) {
            if (e.name().startsWith("webhook-")) {
                continue; // inbound Paystack payloads (BILL-01b): they carry the provider's authorization object by definition
            }
            String text = e.json().toString();
            assertThat(text).as(e.name()).doesNotContain("authorization_code").doesNotContain("\"signature\"");
            for (JsonNode n : e.json().findValues("secretKey")) {
                if (!n.isTextual() || !n.asText().startsWith("sk_")) {
                    continue; // a validation message keyed by the field name
                }
                assertThat(e.kind()).as(e.name() + " shows a secretKey").isIn("valid", "invalid");
                assertThat(n.asText()).contains("DOCSEXAMPLEONLYNOTAKEY");
            }
            assertThat(text).as(e.name()).doesNotContainPattern("sk_(test|live)_(?!DOCSEXAMPLEONLYNOTAKEY)");
        }
    }
}
