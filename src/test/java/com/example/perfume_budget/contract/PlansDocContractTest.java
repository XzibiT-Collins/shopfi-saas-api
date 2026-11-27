package com.example.perfume_budget.contract;

import com.example.perfume_budget.contract.PlansDocExamples.Example;
import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.plan.AbstractPlansIntegrationTest;
import com.example.perfume_budget.platform.repository.TenantRepository;
import com.example.perfume_budget.support.TestPlans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * PLAN-01 AC12 / PLAN-03 AC8: the examples of docs/api/plans.md (and the PLAN-03 examples of platform-tenants.md) against
 * the REAL server on PostgreSQL. Every PLAN-01 response example is produced by its scenario and must validate against its
 * schema and equal the live body (description, data keys, data values where deterministic: {@code updatedAt}, actor
 * e-mails, tenant counts, ids of created rows and timestamps are normalised). Every {@code valid} request example is
 * accepted and every {@code invalid} one rejected with its status. The subscription and 402 examples are PLAN-02's
 * (SubscriptionDocContractTest); here they are only schema-checked ({@link PlansDocSchemaTest}).
 */
class PlansDocContractTest extends AbstractPlansIntegrationTest {

    static final String DOC_ADMIN = "root@platform.example";
    /** Response examples exercised live by PLAN-02, not here. */
    static final Set<String> PLAN_02 = Set.of("subscription-trialing", "subscription-read-only",
            "upgrade-required-not-in-plan", "upgrade-required-inactive", "limit-reached");

    private final PlansDocExamples docs = new PlansDocExamples("plans.md");
    private final PlansDocExamples tenantDocs = new PlansDocExamples("platform-tenants.md");
    private final Set<String> exercised = new LinkedHashSet<>();

    @Autowired TenantRepository tenantRepository;

    // ------------------------------------------------------------------------------------------ helpers

    private String req(String name) {
        return docs.get(name).json().toString();
    }

    /** Status, schema, description and data must agree. {@code normalise} strips non-deterministic values from BOTH. */
    private void assertMatches(String name, MockHttpServletResponse r, boolean normalise) throws Exception {
        Example ex = docs.get(name);
        String raw = r.getContentAsString().replace(admin.getEmail(), DOC_ADMIN);
        assertThat(r.getStatus()).as(name + ": " + raw).isEqualTo(ex.status());
        JsonNode live = JSON.readTree(raw);
        assertThat(docs.validate(ex.schema(), live)).as(name + " violates " + ex.schema() + ": " + raw).isEmpty();
        JsonNode expected = ex.json().deepCopy();
        if (normalise) {
            normalise(expected);
            normalise(live);
        }
        assertThat(live).as(name).isEqualTo(expected);
        exercised.add(name);
    }

    /** Removes the values a live run cannot reproduce: timestamps and tenant counts (they depend on other tests). */
    private static void normalise(JsonNode n) {
        if (n.isObject()) {
            ObjectNode o = (ObjectNode) n;
            for (String f : List.of("updatedAt", "createdAt", "occurredAt", "tenantCounts")) {
                if (o.has(f)) {
                    o.put(f, "<normalised>");
                }
            }
            o.elements().forEachRemaining(PlansDocContractTest::normalise);
        } else if (n.isArray()) {
            n.elements().forEachRemaining(PlansDocContractTest::normalise);
        }
    }

    private static void setVersions(long starter, long growth, long scale) {
        exec("UPDATE master.plans SET version = ?, updated_by = CASE WHEN ? > 0 THEN 'root@platform.example' END WHERE id = 1", starter, starter);
        exec("UPDATE master.plans SET version = ?, updated_by = CASE WHEN ? > 0 THEN 'root@platform.example' END WHERE id = 2", growth, growth);
        exec("UPDATE master.plans SET version = ?, updated_by = CASE WHEN ? > 0 THEN 'root@platform.example' END WHERE id = 3", scale, scale);
    }

    // ------------------------------------------------------------------------------------------ the scenarios

    @Test
    void everyPlan01ResponseExampleEqualsWhatTheServerReturns_andEveryRequestExampleIsHandledAsDocumented() throws Exception {
        // ---- section 4: auth errors
        assertMatches("platform-unauthenticated", call(get(PLANS)), false);
        assertMatches("platform-forbidden", call(get(PLANS).with(user("someone").roles("ADMIN"))), false);

        // ---- section 12: public list = EXACTLY the seed (only updatedAt normalised)
        assertMatches("public-plans", call(get(PUBLIC)), true);

        // ---- section 5: the platform list (seed + the versions / admin of the example)
        setVersions(7, 3, 0);
        assertMatches("platform-plans-list", asAdmin(get(PLANS), null), true);

        // ---- section 6: update (Growth at version 3 -> 4, 44900 at 15 %)
        assertMatches("plan-updated", asAdmin(put(PLANS + "/2"), req("plan-update-request")), true);

        // ---- section 8: the matrix (Growth v4 loses WALK_IN_POS; Starter v7 -> 100 products, 30-day trial)
        assertMatches("matrix-response", asAdmin(put(PLANS + "/matrix"), req("matrix-request")), true);

        // ---- section 6: create (id 4, version 0, everything off)
        assertMatches("plan-created", asAdmin(post(PLANS), req("plan-create-request")), true);

        // ---- errors of sections 6, 7, 8, 9
        assertMatches("plan-validation-error", asAdmin(post(PLANS), req("plan-create-discount-over-100")), false);
        assertMatches("plan-rule-error", asAdmin(put(PLANS + "/1"), planBody(1).put("active", false).toString()), false);
        assertMatches("plan-default-trial-error", asAdmin(put(PLANS + "/1"), planBody(1).put("trialDays", 0).toString()), false);
        ObjectNode taken = (ObjectNode) docs.get("plan-create-request").json().deepCopy();
        taken.put("code", "STARTER");
        assertMatches("plan-code-taken", asAdmin(post(PLANS), taken.toString()), false);
        assertMatches("plan-not-found", asAdmin(put(PLANS + "/9999"), planBody(2).toString()), false);
        assertMatches("matrix-wrong-kind-error", asAdmin(put(PLANS + "/matrix"),
                "{\"plans\":[{\"id\":2,\"version\":5,\"entitlements\":[{\"featureKey\":\"WALK_IN_POS\",\"enabled\":true,\"limitValue\":1}]}]}"), false);
        assertMatches("matrix-unknown-feature-error", asAdmin(put(PLANS + "/matrix"),
                "{\"plans\":[{\"id\":2,\"version\":5,\"entitlements\":[{\"featureKey\":\"RETURNS_REFUNDS\",\"enabled\":true}]}]}"), false);
        assertMatches("matrix-conflict", asAdmin(put(PLANS + "/matrix"),
                "{\"plans\":[{\"id\":2,\"version\":4,\"entitlements\":[{\"featureKey\":\"WALK_IN_POS\",\"enabled\":true}]}]}"), false);

        // ---- section 10: the audit page (shape and values of the rows this scenario wrote)
        MockHttpServletResponse audit = asAdmin(get(PLANS + "/audit"), null);
        assertThat(audit.getStatus()).isEqualTo(200);
        JsonNode page = JSON.readTree(audit.getContentAsString().replace(admin.getEmail(), DOC_ADMIN));
        assertThat(docs.validate("PlanAuditPageEnvelope", page)).as(page.toString()).isEmpty();
        JsonNode docPage = docs.get("plan-audit-page").json();
        Set<String> liveActions = new TreeSet<>();
        page.get("data").get("items").forEach(i -> liveActions.add(i.get("action").asText()));
        assertThat(liveActions).contains("PLAN_CREATE", "PLAN_UPDATE", "PLAN_MATRIX_UPDATE");
        // the documented PLAN_UPDATE (Growth price + discount) and matrix rows are exactly what the server wrote
        JsonNode docUpdate = docPage.get("data").get("items").get(3);
        JsonNode liveUpdate = findRow(page, "PLAN_UPDATE", 2);
        assertThat(liveUpdate.get("changes")).isEqualTo(docUpdate.get("changes"));
        // the doc matrix rows list the headline changes; the live rows also carry the bullet text the example request
        // changed (only changed fields are audited, and the request did change the bullets)
        for (int[] row : new int[][]{{2, 2}, {1, 1}}) {
            JsonNode live = findRow(page, "PLAN_MATRIX_UPDATE", row[1]).get("changes");
            for (JsonNode c : docPage.get("data").get("items").get(row[0]).get("changes")) {
                assertThat(live).as("plan " + row[1]).contains(c);
            }
            live.forEach(c -> assertThat(c.get("field").asText()).as("plan " + row[1])
                    .isIn("trialDays", "marketingBullets", "entitlements.MAX_PRODUCTS", "entitlements.WALK_IN_POS"));
        }
        exercised.add("plan-audit-page");

        // ---- section 11: the tenant plan action
        tenants.acme();
        Tenant acme = tenantRepository.findBySlug("acme").orElseThrow();
        exec("UPDATE master.tenants SET plan_id = 2, subscription_status = 'TRIALING', trial_ends_at = now() + interval '5 days' WHERE id = ?",
                acme.getId());
        MockHttpServletResponse change = asAdmin(put("/api/v1/platform/tenants/acme/plan"), req("tenant-plan-change-request"));
        assertThat(change.getStatus()).as(change.getContentAsString()).isEqualTo(200);
        JsonNode liveTenant = JSON.readTree(change.getContentAsString());
        assertThat(docs.validate("TenantSummaryEnvelope", liveTenant)).as(liveTenant.toString()).isEmpty();
        assertThat(fieldNames(liveTenant.get("data"))).isEqualTo(fieldNames(docs.get("tenant-plan-response").json().get("data")));
        for (String f : List.of("slug", "plan", "planName", "subscriptionStatus", "status")) {
            assertThat(liveTenant.get("data").get(f)).as(f).isEqualTo(docs.get("tenant-plan-response").json().get("data").get(f));
        }
        exercised.add("tenant-plan-response");
        // plan 4 (ENTERPRISE, created above) made inactive: the documented refusal, then the documented override
        exec("UPDATE master.plans SET active = FALSE WHERE id = 4");
        assertMatches("tenant-plan-inactive-error", asAdmin(put("/api/v1/platform/tenants/acme/plan"), "{\"planId\":4}"), false);
        assertThat(asAdmin(put("/api/v1/platform/tenants/acme/plan"), req("tenant-plan-inactive-request")).getStatus()).isEqualTo(200);
        assertThat(asAdmin(put("/api/v1/platform/tenants/acme/plan"), req("tenant-trial-extend-request")).getStatus()).isEqualTo(200);
        assertMatches("tenant-trial-error", asAdmin(put("/api/v1/platform/tenants/acme/plan"), req("tenant-trial-extend-request")), false);
        assertThat(asAdmin(put("/api/v1/platform/tenants/acme/plan"), req("tenant-status-request")).getStatus()).isEqualTo(200);

        // ---- platform-tenants.md (PLAN-03 examples): the live detail and list rows have exactly the documented keys
        JsonNode detail = JSON.readTree(asAdmin(get("/api/v1/platform/tenants/acme"), null).getContentAsString());
        assertThat(tenantDocs.validate("TenantSummaryEnvelope", detail)).as(detail.toString()).isEmpty();
        assertThat(fieldNames(detail.get("data"))).isEqualTo(fieldNames(tenantDocs.get("tenant-detail-with-plan").json().get("data")));
        JsonNode list = JSON.readTree(asAdmin(get("/api/v1/platform/tenants?search=acme"), null).getContentAsString());
        assertThat(tenantDocs.validate("TenantPageEnvelope", list)).as(list.toString()).isEmpty();
        assertThat(fieldNames(list.get("data").get("items").get(0)))
                .isEqualTo(fieldNames(tenantDocs.get("tenant-list-with-plan").json().get("data").get("items").get(0)));

        // ---- public-plans-mixed: the other display branches, after a reset to the seed
        TestPlans.resetToSeed();
        publicPlanCache.evict();
        exec("UPDATE master.plans SET annual_discount_percent = 0 WHERE id = 1");
        exec("UPDATE master.plans SET annual_discount_percent = 15 WHERE id = 2");
        exec("UPDATE master.plans SET annual_discount_percent = 25, trial_days = 0 WHERE id = 3");
        assertMatches("public-plans-mixed", call(get(PUBLIC)), true);

        // every PLAN-01 response example was produced by a scenario above
        Set<String> responses = new TreeSet<>();
        docs.examples.values().stream().filter(e -> e.kind().equals("response")).forEach(e -> responses.add(e.name()));
        responses.removeAll(PLAN_02);
        assertThat(new TreeSet<>(exercised)).isEqualTo(responses);
    }

    private static JsonNode findRow(JsonNode page, String action, long planId) {
        for (JsonNode i : page.get("data").get("items")) {
            if (i.get("action").asText().equals(action) && i.get("planId").asLong() == planId) {
                return i;
            }
        }
        throw new AssertionError("no " + action + " row for plan " + planId + " in " + page);
    }

    private static Set<String> fieldNames(JsonNode n) {
        Set<String> s = new TreeSet<>();
        n.fieldNames().forEachRemaining(s::add);
        return s;
    }

    @Test
    void everyInvalidRequestExampleIsRejectedWithItsStatus_andWritesNothing() throws Exception {
        tenants.acme();
        long audit = auditRows();
        int n = 0;
        for (Example ex : docs.examples.values()) {
            if (!ex.kind().equals("invalid")) {
                continue;
            }
            MockHttpServletRequestBuilder b = switch (ex.schema()) {
                case "PlanCreateRequest" -> post(PLANS);
                case "PlanUpdateRequest" -> put(PLANS + "/2");
                case "PlanMatrixRequest" -> put(PLANS + "/matrix");
                case "TenantPlanUpdateRequest" -> put("/api/v1/platform/tenants/acme/plan");
                default -> throw new AssertionError("no endpoint for " + ex.schema());
            };
            String body = ex.json().toString();
            if (ex.name().equals("plan-update-trial-too-long") || ex.name().equals("plan-update-fee-too-high")) {
                body = body.replace("\"version\":3", "\"version\":0"); // a current version: the 400 is the field, not a 409
            }
            MockHttpServletResponse r = asAdmin(b, body);
            assertThat(r.getStatus()).as(ex.name() + ": " + r.getContentAsString()).isEqualTo(ex.status());
            n++;
        }
        assertThat(n).isGreaterThanOrEqualTo(16);
        assertThat(auditRows()).isEqualTo(audit);
        assertThat(version(1) + version(2) + version(3)).isZero();
    }

    @Test
    void everyValidRequestExampleIsSchemaValid_andTheOnesNotUsedAboveAreAcceptedToo() throws Exception {
        for (Example ex : docs.examples.values()) {
            if (ex.kind().equals("valid")) {
                assertThat(docs.validate(ex.schema(), ex.json())).as(ex.name()).isEmpty();
            }
        }
        // plan-update-request at a current version (the scenario test uses it at version 3)
        ObjectNode upd = (ObjectNode) docs.get("plan-update-request").json().deepCopy();
        upd.put("version", 0);
        assertThat(asAdmin(put(PLANS + "/2"), upd.toString()).getStatus()).isEqualTo(200);
    }
}
