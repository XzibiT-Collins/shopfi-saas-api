package com.example.perfume_budget.entitlement;

import com.example.perfume_budget.platform.plan.PlanFeature;
import com.example.perfume_budget.support.InMemoryEntitlementStore;
import com.example.perfume_budget.support.TestPlans;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * PLAN-02 AC1 / plans.md 15.1 and 18 item 5 on the running application: the entitlement cache is used (a change made
 * behind its back stays invisible until evicted), a platform-side plan write or tenant plan change evicts after commit and
 * the very next tenant request sees it, tenant A's entry is never served to tenant B, and with the store down every answer
 * comes from the database (a plan without the feature still gets 402: never a default grant).
 */
class EntitlementCacheIntegrationTest extends AbstractEntitlementIntegrationTest {

    private static final String QUOTE_BODY = "{\"items\":[{\"productId\":999999,\"quantity\":1}]}";

    private MockHttpServletResponse quote(T t) throws Exception {
        return send(t, t.admin(), req(HttpMethod.POST, "/api/v1/admin/walk-in/quote"), QUOTE_BODY);
    }

    @Test
    void aPlatformMatrixWriteIsSeenByTheNextTenantRequest_withNoRestart() throws Exception {
        T growth = tenant("p2-cache-g");
        T scale = tenant("p2-cache-s");
        setPlan(growth, TestPlans.GROWTH_ID, "TRIALING", null);
        setPlan(scale, TestPlans.SCALE_ID, "TRIALING", null);
        store.mode = InMemoryEntitlementStore.Mode.ON;

        assertPassedTheGate(quote(growth), "growth before");
        assertPassedTheGate(quote(scale), "scale before");
        assertThat(store.values).containsKeys("plans:v1:tenant:" + growth.id(), "plans:v1:tenant:" + scale.id(),
                "plans:v1:entitlements:2", "plans:v1:entitlements:3");
        assertThat(store.values.keySet()).allMatch(k -> k.startsWith("plans:v1:") && !k.contains("p2-cache"));

        // behind the cache's back (SQL, no event): the cached grant is still served -> the cache is really in use
        exec("UPDATE master.plan_entitlements SET enabled = false WHERE plan_id = 2 AND feature_key = 'WALK_IN_POS'");
        assertPassedTheGate(quote(growth), "growth served from the cache");
        exec("UPDATE master.plan_entitlements SET enabled = true WHERE plan_id = 2 AND feature_key = 'WALK_IN_POS'");
        // the same for the tenant entry: a status changed with SQL stays invisible until evicted (or the TTL)
        setPlan(scale, TestPlans.SCALE_ID, "CANCELED", null);
        assertPassedTheGate(quote(scale), "scale tenant state served from the cache");
        setPlan(scale, TestPlans.SCALE_ID, "TRIALING", null);

        // the platform console removes WALK_IN_POS from Growth: evicted after commit, effective on the next request
        MockHttpServletResponse m = asAdmin(put(PLANS + "/matrix"), "{\"plans\":[{\"id\":2,\"version\":" + version(2)
                + ",\"entitlements\":[{\"featureKey\":\"WALK_IN_POS\",\"enabled\":false}]}]}");
        assertThat(m.getStatus()).as(m.getContentAsString()).isEqualTo(200);
        assertThat(store.values).doesNotContainKey("plans:v1:entitlements:2");
        assert402(quote(growth), "PLAN_UPGRADE_REQUIRED", PlanFeature.WALK_IN_POS, "NOT_IN_PLAN", "growth after");
        assertThat(body(quote(growth)).at("/data/requiredPlans").toString()).isEqualTo("[\"SCALE\"]");
        assertPassedTheGate(quote(scale), "scale after");
    }

    @Test
    void aPlatformTenantPlanChangeIsSeenByTheNextRequest() throws Exception {
        T scale = tenant("p2-cache-s");
        setPlan(scale, TestPlans.SCALE_ID, "TRIALING", null);
        store.mode = InMemoryEntitlementStore.Mode.ON;
        assertPassedTheGate(quote(scale), "before");
        assertThat(store.values).containsKey("plans:v1:tenant:" + scale.id());

        MockHttpServletResponse c = asAdmin(put("/api/v1/platform/tenants/p2-cache-s/plan"), "{\"subscriptionStatus\":\"CANCELED\"}");
        assertThat(c.getStatus()).as(c.getContentAsString()).isEqualTo(200);
        assert402(quote(scale), "PLAN_UPGRADE_REQUIRED", PlanFeature.WALK_IN_POS, "SUBSCRIPTION_INACTIVE", "canceled");

        c = asAdmin(put("/api/v1/platform/tenants/p2-cache-s/plan"), "{\"planId\":1}");
        assertThat(c.getStatus()).isEqualTo(200);
        assert402(quote(scale), "PLAN_UPGRADE_REQUIRED", PlanFeature.WALK_IN_POS, "NOT_IN_PLAN", "starter");

        c = asAdmin(put("/api/v1/platform/tenants/p2-cache-s/plan"), "{\"planId\":3,\"subscriptionStatus\":\"ACTIVE\"}");
        assertThat(c.getStatus()).isEqualTo(200);
        assertPassedTheGate(quote(scale), "scale active again");
    }

    @Test
    void tenantAsEntryIsNeverServedToTenantB() throws Exception {
        T a = tenant("p2-cache-g");
        T b = tenant("p2-cache-s");
        setPlan(a, TestPlans.STARTER_ID, "TRIALING", null);
        setPlan(b, TestPlans.SCALE_ID, "TRIALING", null);
        store.mode = InMemoryEntitlementStore.Mode.ON;
        for (int i = 0; i < 3; i++) {
            assert402(quote(a), "PLAN_UPGRADE_REQUIRED", PlanFeature.WALK_IN_POS, "NOT_IN_PLAN", "a " + i);
            assertPassedTheGate(quote(b), "b " + i);
        }
        assertThat(store.values.get("plans:v1:tenant:" + a.id())).contains("\"tenantId\":" + a.id()).contains("\"planId\":1");
        assertThat(store.values.get("plans:v1:tenant:" + b.id())).contains("\"tenantId\":" + b.id()).contains("\"planId\":3");
    }

    @Test
    void storeDown_everyAnswerComesFromTheDatabase_neverADefaultGrant() throws Exception {
        T a = tenant("p2-cache-g");
        T b = tenant("p2-cache-s");
        setPlan(a, TestPlans.STARTER_ID, "TRIALING", null);
        setPlan(b, TestPlans.SCALE_ID, "TRIALING", null);
        store.mode = InMemoryEntitlementStore.Mode.DOWN;
        assert402(quote(a), "PLAN_UPGRADE_REQUIRED", PlanFeature.WALK_IN_POS, "NOT_IN_PLAN", "starter, redis down");
        assertPassedTheGate(quote(b), "scale, redis down");
        setPlan(b, TestPlans.SCALE_ID, "CANCELED", null);
        assert402(quote(b), "PLAN_UPGRADE_REQUIRED", PlanFeature.WALK_IN_POS, "SUBSCRIPTION_INACTIVE", "canceled, redis down");
        // the platform write still commits when the eviction cannot reach the store
        store.mode = InMemoryEntitlementStore.Mode.DOWN;
        assertThat(asAdmin(put(PLANS + "/matrix"), "{\"plans\":[{\"id\":3,\"version\":" + version(3)
                + ",\"entitlements\":[{\"featureKey\":\"WALK_IN_POS\",\"enabled\":false}]}]}").getStatus()).isEqualTo(200);
    }
}
