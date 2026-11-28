package com.example.perfume_budget.contract;

import com.example.perfume_budget.contract.PlansDocExamples.Example;
import com.example.perfume_budget.entitlement.AbstractEntitlementIntegrationTest;
import com.example.perfume_budget.platform.plan.PlanFeature;
import com.example.perfume_budget.support.TestPlans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLAN-02 (plans.md 13 and 14, AC8): the subscription and 402 examples of docs/api/plans.md against the REAL server. Each
 * scenario reproduces the example's state exactly (plan, status, trial end, usage counts, the clock at 2026-11-27) and the
 * live body must validate against its schema and equal the example (only {@code updatedAt} normalised). Plus the access
 * rules of the subscription endpoint: tenant only from TenantContext, ADMIN and FRONT_DESK, CUSTOMER 403, anonymous and
 * foreign-tenant tokens 401, the public plan shape only, {@code publicPlans} = the public endpoint's list.
 */
class SubscriptionDocContractTest extends AbstractEntitlementIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-27T12:00:00Z");
    private final PlansDocExamples docs = new PlansDocExamples("plans.md");

    @BeforeEach
    void fixedClock() {
        setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void assertMatches(String name, MockHttpServletResponse r) throws Exception {
        Example ex = docs.get(name);
        String raw = r.getContentAsString();
        assertThat(r.getStatus()).as(name + ": " + raw).isEqualTo(ex.status());
        JsonNode live = JSON.readTree(raw);
        assertThat(docs.validate(ex.schema(), live)).as(name + " violates " + ex.schema() + ": " + raw).isEmpty();
        JsonNode expected = ex.json().deepCopy();
        normalise(expected);
        normalise(live);
        assertThat(live).as(name).isEqualTo(expected);
    }

    private static void normalise(JsonNode n) {
        if (n.isObject()) {
            if (n.has("updatedAt")) {
                ((ObjectNode) n).put("updatedAt", "<normalised>");
            }
            n.elements().forEachRemaining(SubscriptionDocContractTest::normalise);
        } else if (n.isArray()) {
            n.elements().forEachRemaining(SubscriptionDocContractTest::normalise);
        }
    }

    /** Usage exactly as an example states it (fresh tenant: only additions are needed). */
    private void usage(T t, long products, long coupons, long locations, long staff) {
        fillTo(t, PlanFeature.MAX_PRODUCTS, products);
        fillTo(t, PlanFeature.MAX_ACTIVE_COUPONS, coupons);
        trimLocations(t, locations);
        fillTo(t, PlanFeature.MAX_LOCATIONS, locations);
        fillTo(t, PlanFeature.MAX_STAFF, staff);
        assertThat(List.of(count(t, PlanFeature.MAX_PRODUCTS), count(t, PlanFeature.MAX_ACTIVE_COUPONS),
                count(t, PlanFeature.MAX_LOCATIONS), count(t, PlanFeature.MAX_STAFF)))
                .as("the example's usage").containsExactly(products, coupons, locations, staff);
    }

    private MockHttpServletResponse subscription(T t, Cookie who) throws Exception {
        return send(t, who, req(HttpMethod.GET, "/api/v1/admin/subscription"), null);
    }

    @Test
    void subscriptionTrialing_growth() throws Exception {
        T t = tenant("p2-doc-growth");
        setPlan(t, TestPlans.GROWTH_ID, "TRIALING", Instant.parse("2026-12-11T09:30:00Z"));
        usage(t, 32, 4, 1, 2);
        assertMatches("subscription-trialing", subscription(t, t.admin()));
    }

    @Test
    void subscriptionReadOnly_starterAfterItsTrial_and402Examples() throws Exception {
        T t = tenant("p2-doc-starter");
        setPlan(t, TestPlans.STARTER_ID, "TRIALING", Instant.parse("2026-11-20T08:00:00Z"));
        usage(t, 50, 1, 1, 0);
        assertMatches("subscription-read-only", subscription(t, t.admin()));
        // read-only: a product create (limit-checked) is 402 SUBSCRIPTION_INACTIVE for the LIMIT key
        assertMatches("upgrade-required-inactive", send(t, t.admin(), newProduct("P2 doc"), null));
        // the same tenant with its trial running: at its limit of 50
        setPlan(t, TestPlans.STARTER_ID, "TRIALING", Instant.parse("2026-12-11T09:30:00Z"));
        assertMatches("limit-reached", send(t, t.admin(), newProduct("P2 doc"), null));
        // a starter tenant starting a walk-in sale
        assertMatches("upgrade-required-not-in-plan", send(t, t.admin(), req(HttpMethod.POST, "/api/v1/admin/walk-in/order"),
                "{\"items\":[{\"productId\":1,\"quantity\":1}],\"paymentMethod\":\"CASH\",\"amountPaid\":10}"));
    }

    @Test
    void publicPlansIsThePublicList_andPlansHaveThePublicShapeOnly_evenForAnInactiveOwnPlan() throws Exception {
        T t = tenant("p2-doc-shape");
        createNonePlan();
        setPlan(t, NONE_ID, "ACTIVE", null);
        MockHttpServletResponse r = subscription(t, t.admin());
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        assertThat(r.getHeaders("Set-Cookie")).isEmpty();
        assertThat(r.getHeader("Cache-Control")).contains("no-store");
        JsonNode d = body(r).get("data");
        assertThat(d.get("plan").get("code").asText()).isEqualTo("NOTHING");
        JsonNode publicList = body(call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(PUBLIC))).get("data").get("plans");
        assertThat(d.get("publicPlans")).isEqualTo(publicList);
        Set<String> keys = new TreeSet<>();
        d.get("plan").fieldNames().forEachRemaining(keys::add);
        assertThat(keys).doesNotContain("version", "updatedBy", "tenantCounts");
        for (JsonNode p : d.get("publicPlans")) {
            Set<String> k = new TreeSet<>();
            p.fieldNames().forEachRemaining(k::add);
            assertThat(k).isEqualTo(keys);
            assertThat(p.get("code").asText()).isNotEqualTo("NOTHING");
        }
        assertThat(d.get("entitlements")).isEqualTo(d.get("plan").get("entitlements"));
        assertThat(d.get("usage")).hasSize(4);
    }

    @Test
    void accessRules_tenantOnlyFromTheContext() throws Exception {
        T a = tenant("p2-doc-growth");
        T b = tenant("p2-doc-starter");
        setPlan(a, TestPlans.GROWTH_ID, "TRIALING", null);
        setPlan(b, TestPlans.STARTER_ID, "TRIALING", null);
        long desk = user(a, "FRONT_DESK", true);
        long customer = user(a, "CUSTOMER", true);
        assertThat(subscription(a, a.admin()).getStatus()).isEqualTo(200);
        MockHttpServletResponse d = subscription(a, token(a.t(), email(a, desk), "FRONT_DESK", desk));
        assertThat(d.getStatus()).as("FRONT_DESK reads it (the upgrade hints of the shell)").isEqualTo(200);
        assertThat(subscription(a, token(a.t(), email(a, customer), "CUSTOMER", customer)).getStatus()).isEqualTo(403);
        assertThat(subscription(a, null).getStatus()).isEqualTo(401);
        assertThat(subscription(b, a.admin()).getStatus()).as("A's token on B's host").isEqualTo(401);
        // nothing in the request selects a tenant: a query parameter naming another tenant is ignored
        MockHttpServletResponse q = send(a, a.admin(), req(HttpMethod.GET, "/api/v1/admin/subscription?tenant=p2-doc-starter&tenantId="
                + b.id()), null);
        assertThat(body(q).at("/data/plan/code").asText()).isEqualTo("GROWTH");
        // and the platform cookie never reads it
        assertThat(send(a, adminCookie, req(HttpMethod.GET, "/api/v1/admin/subscription"), null).getStatus()).isEqualTo(401);
    }

    @Test
    void theSubscriptionEndpointIsNeverGated_alsoWhenReadOnly() throws Exception {
        T t = tenant("p2-doc-shape");
        createNonePlan();
        setPlan(t, NONE_ID, "CANCELED", null);
        MockHttpServletResponse r = subscription(t, t.admin());
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(body(r).at("/data/readOnly").asBoolean()).isTrue();
    }
}
