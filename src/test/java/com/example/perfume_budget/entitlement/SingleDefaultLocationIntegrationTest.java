package com.example.perfume_budget.entitlement;

import com.example.perfume_budget.platform.plan.PlanFeature;
import com.example.perfume_budget.support.TestPlans;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLAN-07 AC2: a freshly provisioned tenant (tenant migrations V1..V51 into a new schema, as the provisioning saga runs
 * them) has exactly ONE storage location, 'Shop Floor', holding all three default roles. On Starter and Growth
 * (MAX_LOCATIONS = 1) the subscription usage is 1 / 1 and a second location is 402 PLAN_LIMIT_REACHED; Scale (unlimited)
 * can still add store rooms.
 */
class SingleDefaultLocationIntegrationTest extends AbstractEntitlementIntegrationTest {

    private static List<String> locations(String schema) {
        List<String> out = new ArrayList<>();
        long n = scalar("SELECT count(*) FROM " + schema + ".storage_locations");
        for (long i = 0; i < n; i++) {
            out.add(string("SELECT name || '|' || type || '|' || active || '|' || is_default_receiving || '|' || is_walk_in_sale_source "
                    + "|| '|' || is_ecommerce_fulfilment_source FROM " + schema + ".storage_locations ORDER BY id OFFSET ? LIMIT 1", i));
        }
        return out;
    }

    private MockHttpServletResponse locationCreate(T t, String name) throws Exception {
        return send(t, t.admin(), req(HttpMethod.POST, "/api/v1/admin/inventory/locations"),
                "{\"name\":\"" + name + "\",\"type\":\"STORE_ROOM\"}");
    }

    private JsonNode locationUsage(T t) throws Exception {
        MockHttpServletResponse r = send(t, t.admin(), req(HttpMethod.GET, "/api/v1/admin/subscription"), null);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        for (JsonNode u : body(r).at("/data/usage")) {
            if (u.get("featureKey").asText().equals("MAX_LOCATIONS")) {
                return u;
            }
        }
        throw new AssertionError("no MAX_LOCATIONS usage: " + r.getContentAsString());
    }

    @Test
    void theTemplateSchema_hasOnlyTheShopFloor() {
        assertThat(locations("tenant_template")).containsExactly("Shop Floor|SHOP_FLOOR|true|true|true|true");
    }

    @Test
    void freshStarterTenant_hasOneLocation_usageOneOfOne_andASecondIsALimitReached() throws Exception {
        T t = tenant("p7-starter");
        setPlan(t, TestPlans.STARTER_ID, "TRIALING", null);
        assertThat(locations(t.schema())).containsExactly("Shop Floor|SHOP_FLOOR|true|true|true|true");
        assertThat(count(t, PlanFeature.MAX_LOCATIONS)).isEqualTo(1);

        JsonNode usage = locationUsage(t);
        assertThat(usage.get("used").asLong()).isEqualTo(1);
        assertThat(usage.get("limit").asInt()).isEqualTo(1);

        MockHttpServletResponse second = locationCreate(t, "P7 Room");
        assert402(second, "PLAN_LIMIT_REACHED", PlanFeature.MAX_LOCATIONS, null, "second location on Starter");
        assertThat(body(second).at("/data/used").asLong()).isEqualTo(1);
        assertThat(body(second).at("/data/limit").asInt()).isEqualTo(1);
        assertThat(count(t, PlanFeature.MAX_LOCATIONS)).isEqualTo(1);
    }

    @Test
    void freshGrowthTenant_usageOneOfOne() throws Exception {
        T t = tenant("p7-growth");
        setPlan(t, TestPlans.GROWTH_ID, "TRIALING", null);
        JsonNode usage = locationUsage(t);
        assertThat(usage.get("used").asLong()).isEqualTo(1);
        assertThat(usage.get("limit").asInt()).isEqualTo(1);
        assert402(locationCreate(t, "P7 Growth Room"), "PLAN_LIMIT_REACHED", PlanFeature.MAX_LOCATIONS, null, "second location on Growth");
    }

    @Test
    void freshScaleTenant_canStillAddStoreRooms() throws Exception {
        T t = tenant("p7-scale");
        setPlan(t, TestPlans.SCALE_ID, "ACTIVE", null);
        assertThat(count(t, PlanFeature.MAX_LOCATIONS)).isEqualTo(1);
        MockHttpServletResponse r1 = locationCreate(t, "P7 Store Room A");
        assertThat(r1.getStatus()).as(r1.getContentAsString()).isEqualTo(200);
        MockHttpServletResponse r2 = locationCreate(t, "P7 Store Room B");
        assertThat(r2.getStatus()).as(r2.getContentAsString()).isEqualTo(200);
        assertThat(locations(t.schema())).containsExactly(
                "Shop Floor|SHOP_FLOOR|true|true|true|true",
                "P7 Store Room A|STORE_ROOM|true|false|false|false",
                "P7 Store Room B|STORE_ROOM|true|false|false|false");
        JsonNode usage = locationUsage(t);
        assertThat(usage.get("used").asLong()).isEqualTo(3);
        assertThat(usage.get("limit").isNull()).isTrue();
    }
}
