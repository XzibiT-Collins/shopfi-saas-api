package com.example.perfume_budget.contract;

import com.example.perfume_budget.platform.plan.PlanFeature;
import com.example.perfume_budget.platform.plan.PlanPricing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLAN-01 (no Spring, no database): every tagged example of plans.md and the PLAN-03 examples of platform-tenants.md
 * agree with plans.schema.json, and every plan in any example carries the derived prices of THE server function and one
 * entitlement per catalog key in catalog order. The server half is {@link PlansDocContractTest}.
 */
class PlansDocSchemaTest {

    private final PlansDocExamples plans = new PlansDocExamples("plans.md");
    private final PlansDocExamples tenants = new PlansDocExamples("platform-tenants.md");

    @Test
    void schemaIsValidDraft2020_12() {
        var meta = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(URI.create("https://json-schema.org/draft/2020-12/schema"));
        assertThat(meta.validate(plans.schemaDoc)).isEmpty();
    }

    @Test
    void everyExampleNamesAKnownSchema_andHasAStatusExactlyWhenItShould() {
        assertThat(plans.examples).hasSizeGreaterThanOrEqualTo(48);
        for (PlansDocExamples d : List.of(plans, tenants)) {
            d.examples.values().forEach(e -> {
                assertThat(d.schemaDoc.get("$defs").has(e.schema())).as(e.name() + " -> " + e.schema()).isTrue();
                assertThat(e.status() != null).as(e.name()).isEqualTo(!e.kind().equals("valid"));
            });
        }
        assertThat(tenants.examples.keySet()).containsExactlyInAnyOrder("tenant-list-with-plan", "tenant-detail-with-plan");
    }

    @Test
    void validAndResponseExamplesPassTheirSchema_invalidOnesFailIt() {
        for (PlansDocExamples d : List.of(plans, tenants)) {
            d.examples.values().forEach(e -> {
                if (e.kind().equals("invalid")) {
                    assertThat(d.validate(e.schema(), e.json())).as(e.name() + " must violate " + e.schema()).isNotEmpty();
                } else {
                    assertThat(d.validate(e.schema(), e.json())).as(e.name()).isEmpty();
                }
            });
        }
    }

    /** The derived annual fields of every documented plan are what PlanPricing computes; entitlements are in catalog order. */
    @Test
    void everyDocumentedPlanUsesTheServerPriceFunction_andTheCatalogOrder() {
        List<String> catalog = Arrays.stream(PlanFeature.values()).map(Enum::name).toList();
        int checked = 0;
        for (PlansDocExamples.Example e : plans.examples.values()) {
            for (JsonNode p : plansOf(e.json())) {
                PlanPricing.Derived d = PlanPricing.derive(p.get("monthlyPricePesewas").asLong(), p.get("annualDiscountPercent").asInt());
                assertThat(p.get("annualPricePesewas").asLong()).as(e.name()).isEqualTo(d.annualPricePesewas());
                assertThat(p.get("annualMonthlyEquivalentPesewas").asLong()).as(e.name()).isEqualTo(d.annualMonthlyEquivalentPesewas());
                assertThat(p.get("annualSavingPesewas").asLong()).as(e.name()).isEqualTo(d.annualSavingPesewas());
                List<String> keys = new ArrayList<>();
                p.get("entitlements").forEach(x -> keys.add(x.get("featureKey").asText()));
                assertThat(keys).as(e.name() + " " + p.get("code")).isEqualTo(catalog);
                checked++;
            }
            JsonNode features = e.json().path("data").path("features");
            if (features.isArray()) {
                List<String> fk = new ArrayList<>();
                features.forEach(f -> fk.add(f.get("key").asText() + "/" + f.get("kind").asText() + "/" + f.get("label").asText() + "/" + f.get("group").asText()));
                assertThat(fk).as(e.name()).isEqualTo(Arrays.stream(PlanFeature.values())
                        .map(f -> f.name() + "/" + f.kind() + "/" + f.label() + "/" + f.group()).toList());
            }
        }
        assertThat(checked).isGreaterThanOrEqualTo(15);
    }

    /** The public shape never carries version / updatedBy / tenantCounts; the schema refuses them (Security requirement 2). */
    @Test
    void schemaRefusesPlatformFieldsInThePublicShape() {
        JsonNode ok = plans.get("public-plans").json();
        for (String leak : List.of("version", "updatedBy", "tenantCounts")) {
            JsonNode copy = ok.deepCopy();
            ((ObjectNode) copy.get("data").get("plans").get(0)).put(leak, 1);
            assertThat(plans.validate("PublicPlansEnvelope", copy)).as(leak).isNotEmpty();
        }
    }

    static List<JsonNode> plansOf(JsonNode body) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode data = body.path("data");
        if (data.has("monthlyPricePesewas")) {
            out.add(data);
        }
        data.path("plans").forEach(out::add);
        data.path("publicPlans").forEach(out::add);
        if (data.path("plan").has("monthlyPricePesewas")) {
            out.add(data.get("plan"));
        }
        return out;
    }
}
