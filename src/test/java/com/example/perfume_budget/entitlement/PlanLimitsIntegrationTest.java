package com.example.perfume_budget.entitlement;

import com.example.perfume_budget.platform.plan.PlanFeature;
import com.example.perfume_budget.repository.CouponRepository;
import com.example.perfume_budget.support.TestPlans;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLAN-02 AC4/AC5 and plans.md 15.2: every write that moves a row into a counted set is limit-checked in the service
 * (incl. {@code assign}, re-activation, coupon activation); {@code limit - 1} passes, {@code limit} answers 402 with
 * {@code limit} and {@code used}; count and insert are serialized (exactly one of N concurrent entries at limit-1); a
 * lowered limit keeps every existing row readable / editable / deactivatable / deletable; the automatic coupon release
 * never takes the tenant above the limit and never answers 402.
 */
class PlanLimitsIntegrationTest extends AbstractEntitlementIntegrationTest {

    @Autowired CouponRepository couponRepository;
    @Autowired PlatformTransactionManager txManager;

    private T growthTenant(String slug) {
        T t = tenant(slug);
        setPlan(t, TestPlans.GROWTH_ID, "TRIALING", null);
        return t;
    }

    private MockHttpServletResponse locationCreate(T t) throws Exception {
        return send(t, t.admin(), req(HttpMethod.POST, "/api/v1/admin/inventory/locations"),
                "{\"name\":\"P2 L " + N.incrementAndGet() + "\",\"type\":\"STORE_ROOM\"}");
    }

    private MockHttpServletResponse couponCreate(T t, boolean active) throws Exception {
        return send(t, t.admin(), req(HttpMethod.POST, "/api/v1/coupon/add-coupon"), couponJson("P2K" + N.incrementAndGet(), active));
    }

    private MockHttpServletResponse staffCreate(T t, boolean active) throws Exception {
        return send(t, t.admin(), req(HttpMethod.POST, "/api/v1/admin/front-desk/users"), "{\"fullName\":\"P2 Staff\",\"email\":\"p2-s"
                + N.incrementAndGet() + "@x.test\",\"password\":\"Str0ng!pass\",\"isActive\":" + active + "}");
    }

    private MockHttpServletResponse productCreate(T t) throws Exception {
        return send(t, t.admin(), newProduct("P2 product " + N.incrementAndGet()), null);
    }

    private void limitReached(MockHttpServletResponse r, PlanFeature f, int limit, long used) throws Exception {
        assert402(r, "PLAN_LIMIT_REACHED", f, null, f.name());
        assertThat(body(r).at("/data/limit").asInt()).isEqualTo(limit);
        assertThat(body(r).at("/data/used").asLong()).isEqualTo(used);
        List<String> required = new ArrayList<>();
        body(r).at("/data/requiredPlans").forEach(n -> required.add(n.asText()));
        // public active plans whose limit is unlimited or above the current usage (14.3)
        List<String> expected = new ArrayList<>();
        for (long p : new long[]{TestPlans.STARTER_ID, TestPlans.GROWTH_ID, TestPlans.SCALE_ID}) {
            Integer l = limit(p, f);
            if (l == null || l > used) {
                expected.add(code(p));
            }
        }
        assertThat(required).isEqualTo(expected);
    }

    @Test
    void limitMinusOnePasses_limitAnswers402_forEveryLimitFeature_inTheirOwnService() throws Exception {
        T t = growthTenant("p2-limits");
        // MAX_LOCATIONS: every location counts, active or not (counted here with SQL of its own)
        exec("INSERT INTO " + t.schema() + ".storage_locations (name, type, active, created_at) VALUES (?, 'STORE_ROOM', false, now())",
                "P2 inactive " + N.incrementAndGet());
        long used = scalar("SELECT count(*) FROM " + t.schema() + ".storage_locations");
        setLimit(TestPlans.GROWTH_ID, PlanFeature.MAX_LOCATIONS, (int) used + 1);
        assertThat(locationCreate(t).getStatus()).isEqualTo(200);
        limitReached(locationCreate(t), PlanFeature.MAX_LOCATIONS, (int) used + 1, used + 1);

        // MAX_ACTIVE_COUPONS: an inactive coupon is outside the set and never limit-checked
        used = count(t, PlanFeature.MAX_ACTIVE_COUPONS);
        setLimit(TestPlans.GROWTH_ID, PlanFeature.MAX_ACTIVE_COUPONS, (int) used + 1);
        assertThat(couponCreate(t, true).getStatus()).isEqualTo(200);
        limitReached(couponCreate(t, true), PlanFeature.MAX_ACTIVE_COUPONS, (int) used + 1, used + 1);
        assertThat(couponCreate(t, false).getStatus()).as("inactive create is not an entry").isEqualTo(200);

        // MAX_STAFF: create active; an inactive create is not an entry
        used = count(t, PlanFeature.MAX_STAFF);
        setLimit(TestPlans.GROWTH_ID, PlanFeature.MAX_STAFF, (int) used + 1);
        assertThat(staffCreate(t, true).getStatus()).isEqualTo(200);
        limitReached(staffCreate(t, true), PlanFeature.MAX_STAFF, (int) used + 1, used + 1);
        assertThat(staffCreate(t, false).getStatus()).isEqualTo(200);

        // MAX_PRODUCTS: new product (new family)
        used = count(t, PlanFeature.MAX_PRODUCTS);
        setLimit(TestPlans.GROWTH_ID, PlanFeature.MAX_PRODUCTS, (int) used + 1);
        assertThat(productCreate(t).getStatus()).isEqualTo(200);
        limitReached(productCreate(t), PlanFeature.MAX_PRODUCTS, (int) used + 1, used + 1);
        assertThat(count(t, PlanFeature.MAX_PRODUCTS)).isEqualTo(used + 1);

        // limit null = unlimited, 0 = none
        setLimit(TestPlans.GROWTH_ID, PlanFeature.MAX_LOCATIONS, null);
        assertThat(locationCreate(t).getStatus()).isEqualTo(200);
        setLimit(TestPlans.GROWTH_ID, PlanFeature.MAX_LOCATIONS, 0);
        assert402(locationCreate(t), "PLAN_LIMIT_REACHED", PlanFeature.MAX_LOCATIONS, null, "limit 0");
    }

    @Test
    void aVariantIntoAnExistingFamilyIsAProductEntryToo() throws Exception {
        T t = growthTenant("p2-variant");
        assertThat(productCreate(t).getStatus()).isEqualTo(200);
        long family = scalar("SELECT max(family_id) FROM " + t.schema() + ".products");
        long used = count(t, PlanFeature.MAX_PRODUCTS);
        setLimit(TestPlans.GROWTH_ID, PlanFeature.MAX_PRODUCTS, (int) used);
        MockHttpServletResponse r = send(t, t.admin(), newProduct("P2 box").param("isNewProduct", "false")
                .param("familyId", String.valueOf(family)).param("uomCode", "BOX").param("conversionFactor", "6"), null);
        limitReached(r, PlanFeature.MAX_PRODUCTS, (int) used, used);
        setLimit(TestPlans.GROWTH_ID, PlanFeature.MAX_PRODUCTS, (int) used + 1);
        assertThat(send(t, t.admin(), newProduct("P2 box").param("isNewProduct", "false")
                .param("familyId", String.valueOf(family)).param("uomCode", "BOX").param("conversionFactor", "6"), null).getStatus())
                .isEqualTo(200);
    }

    /** Security H1: assign makes an active FRONT_DESK user; re-activation of an assigned-but-inactive user is an entry. */
    @Test
    void assignAndReactivationAreEntries_deactivationAndEditsAreNot() throws Exception {
        T t = growthTenant("p2-staff");
        long used = count(t, PlanFeature.MAX_STAFF);
        setLimit(TestPlans.GROWTH_ID, PlanFeature.MAX_STAFF, (int) used);
        long customer = user(t, "CUSTOMER", true);
        MockHttpServletResponse assign = send(t, t.admin(), req(HttpMethod.PATCH, "/api/v1/admin/front-desk/users/" + customer + "/assign"), null);
        limitReached(assign, PlanFeature.MAX_STAFF, (int) used, used);
        assertThat(string("SELECT roles FROM " + t.schema() + ".users WHERE id = ?", customer)).isEqualTo("CUSTOMER");
        // an inactive account assigned is not an entry ...
        long dormant = user(t, "CUSTOMER", false);
        assertThat(send(t, t.admin(), req(HttpMethod.PATCH, "/api/v1/admin/front-desk/users/" + dormant + "/assign"), null).getStatus())
                .isEqualTo(200);
        // ... but its later re-activation is
        limitReached(send(t, t.admin(), req(HttpMethod.PATCH, "/api/v1/admin/front-desk/users/" + dormant), "{\"isActive\":true}"),
                PlanFeature.MAX_STAFF, (int) used, used);
        // editing or deactivating counted users is never limit-checked
        long desk = scalar("SELECT max(id) FROM " + t.schema() + ".users WHERE roles = 'FRONT_DESK' AND is_active");
        if (desk > 0) {
            assertThat(send(t, t.admin(), req(HttpMethod.PATCH, "/api/v1/admin/front-desk/users/" + desk), "{\"fullName\":\"Renamed\"}")
                    .getStatus()).isEqualTo(200);
            assertThat(send(t, t.admin(), req(HttpMethod.PATCH, "/api/v1/admin/front-desk/users/" + desk), "{\"isActive\":false}")
                    .getStatus()).isEqualTo(200);
            assertThat(send(t, t.admin(), req(HttpMethod.PATCH, "/api/v1/admin/front-desk/users/" + dormant), "{\"isActive\":true}")
                    .getStatus()).as("a slot freed by the deactivation").isEqualTo(200);
        }
    }

    /** AC5: lowering a limit below usage keeps every row readable, editable, deactivatable, deletable; only entries are 402. */
    @Test
    void loweringALimitKeepsTheData_andOnlyBlocksNewEntries() throws Exception {
        T t = growthTenant("p2-lower");
        for (PlanFeature f : List.of(PlanFeature.MAX_LOCATIONS, PlanFeature.MAX_ACTIVE_COUPONS, PlanFeature.MAX_STAFF, PlanFeature.MAX_PRODUCTS)) {
            setLimit(TestPlans.GROWTH_ID, f, null);
        }
        for (int i = 0; i < 2; i++) {
            assertThat(locationCreate(t).getStatus()).isEqualTo(200);
            assertThat(couponCreate(t, true).getStatus()).isEqualTo(200);
            assertThat(staffCreate(t, true).getStatus()).isEqualTo(200);
            assertThat(productCreate(t).getStatus()).isEqualTo(200);
        }
        for (PlanFeature f : List.of(PlanFeature.MAX_LOCATIONS, PlanFeature.MAX_ACTIVE_COUPONS, PlanFeature.MAX_STAFF, PlanFeature.MAX_PRODUCTS)) {
            setLimit(TestPlans.GROWTH_ID, f, 1);
        }
        // locations: list, edit (also activation: inactive locations already count), still all there
        long locations = count(t, PlanFeature.MAX_LOCATIONS);
        long loc = scalar("SELECT max(id) FROM " + t.schema() + ".storage_locations");
        assertThat(send(t, t.admin(), req(HttpMethod.GET, "/api/v1/admin/inventory/locations"), null).getStatus()).isEqualTo(200);
        assertThat(send(t, t.admin(), req(HttpMethod.PUT, "/api/v1/admin/inventory/locations/" + loc),
                "{\"name\":\"P2 renamed " + loc + "\",\"type\":\"SHOP_FLOOR\",\"active\":false}").getStatus()).isEqualTo(200);
        assertThat(send(t, t.admin(), req(HttpMethod.PUT, "/api/v1/admin/inventory/locations/" + loc),
                "{\"name\":\"P2 renamed " + loc + "\",\"type\":\"SHOP_FLOOR\",\"active\":true}").getStatus()).isEqualTo(200);
        assert402(locationCreate(t), "PLAN_LIMIT_REACHED", PlanFeature.MAX_LOCATIONS, null, "location");
        assertThat(count(t, PlanFeature.MAX_LOCATIONS)).isEqualTo(locations);

        // coupons: read, edit an active one (already counted), deactivate, delete
        long coupon = scalar("SELECT max(id) FROM " + t.schema() + ".coupons WHERE is_active");
        assertThat(send(t, t.admin(), req(HttpMethod.GET, "/api/v1/coupon/" + coupon), null).getStatus()).isEqualTo(200);
        assertThat(send(t, t.admin(), req(HttpMethod.PUT, "/api/v1/coupon/" + coupon), couponJson(null, true)).getStatus()).isEqualTo(200);
        assert402(couponCreate(t, true), "PLAN_LIMIT_REACHED", PlanFeature.MAX_ACTIVE_COUPONS, null, "coupon");
        assertThat(send(t, t.admin(), req(HttpMethod.POST, "/api/v1/coupon/" + coupon + "/deactivate"), null).getStatus()).isEqualTo(200);
        assertThat(send(t, t.admin(), req(HttpMethod.DELETE, "/api/v1/coupon/" + coupon), null).getStatus()).isIn(200, 204);

        // staff: list, edit
        long desk = scalar("SELECT max(id) FROM " + t.schema() + ".users WHERE roles = 'FRONT_DESK' AND is_active");
        assertThat(send(t, t.admin(), req(HttpMethod.GET, "/api/v1/admin/front-desk/users"), null).getStatus()).isEqualTo(200);
        assertThat(send(t, t.admin(), req(HttpMethod.PATCH, "/api/v1/admin/front-desk/users/" + desk), "{\"fullName\":\"Kept\"}")
                .getStatus()).isEqualTo(200);
        assert402(staffCreate(t, true), "PLAN_LIMIT_REACHED", PlanFeature.MAX_STAFF, null, "staff");

        // products: read, edit, delete
        long product = scalar("SELECT max(id) FROM " + t.schema() + ".products");
        assertThat(send(t, t.admin(), req(HttpMethod.GET, "/api/v1/product/main/" + product), null).getStatus()).isEqualTo(200);
        assertThat(send(t, t.admin(), MockMvcRequestBuilders.multipart(HttpMethod.PUT, "/api/v1/product/update/" + product)
                .param("productName", "P2 renamed").param("currency", "GHS").param("sellingPrice", "120.00")
                .param("costPrice", "60.00"), null).getStatus()).isEqualTo(200);
        assert402(productCreate(t), "PLAN_LIMIT_REACHED", PlanFeature.MAX_PRODUCTS, null, "product");
        assertThat(send(t, t.admin(), req(HttpMethod.DELETE, "/api/v1/product/delete/" + product), null).getStatus()).isIn(200, 204);
    }

    /** 15.2: count + insert serialized per tenant and feature; at limit - 1 exactly one of N concurrent entries passes. */
    @Test
    void concurrentEntriesAtLimitMinusOne_exactlyOnePasses() throws Exception {
        T t = growthTenant("p2-race");
        for (PlanFeature f : List.of(PlanFeature.MAX_LOCATIONS, PlanFeature.MAX_ACTIVE_COUPONS, PlanFeature.MAX_PRODUCTS)) {
            long used = count(t, f);
            setLimit(TestPlans.GROWTH_ID, f, (int) used + 1);
            List<Integer> statuses = parallel(4, i -> () -> (f == PlanFeature.MAX_LOCATIONS ? locationCreate(t)
                    : f == PlanFeature.MAX_ACTIVE_COUPONS ? couponCreate(t, true) : productCreate(t)).getStatus());
            assertThat(statuses).as(f.name()).containsOnly(200, 402);
            assertThat(statuses.stream().filter(s -> s == 200).count()).as(f.name()).isEqualTo(1);
            assertThat(count(t, f)).isEqualTo(used + 1);
        }
    }

    /**
     * QA N2 / PO: exhausted coupons count, so the system re-activation of a released redemption only moves a coupon
     * inside the set: it never pushes the tenant above the limit and the core path never answers 402.
     */
    @Test
    void aReleasedRedemptionNeverTakesTheTenantAboveTheLimit() throws Exception {
        T t = growthTenant("p2-release");
        long exhausted = scalar("INSERT INTO " + t.schema() + ".coupons (code, discount_type, discount_value, usage_limit, usage_count, "
                + "is_active, limit_deactivated, start_date, expiration_date, created_at) VALUES ('P2EXH" + N.incrementAndGet()
                + "', 'FLAT', 5, 1, 1, false, true, DATE '2026-01-01', DATE '2099-12-31', now()) RETURNING id");
        long exhausted2 = scalar("INSERT INTO " + t.schema() + ".coupons (code, discount_type, discount_value, usage_limit, usage_count, "
                + "is_active, limit_deactivated, start_date, expiration_date, created_at) VALUES ('P2EXH" + N.incrementAndGet()
                + "', 'FLAT', 5, 1, 1, false, true, DATE '2026-01-01', DATE '2099-12-31', now()) RETURNING id");
        long used = count(t, PlanFeature.MAX_ACTIVE_COUPONS);
        setLimit(TestPlans.GROWTH_ID, PlanFeature.MAX_ACTIVE_COUPONS, (int) used);
        assert402(couponCreate(t, true), "PLAN_LIMIT_REACHED", PlanFeature.MAX_ACTIVE_COUPONS, null, "at the limit");
        // an admin PUT isActive:true on an exhausted (already counted) coupon is not an entry: never 402, even at the limit
        assertThat(send(t, t.admin(), req(HttpMethod.PUT, "/api/v1/coupon/" + exhausted2), couponJson(null, true)).getStatus())
                .isEqualTo(200);
        assertThat(count(t, PlanFeature.MAX_ACTIVE_COUPONS)).isEqualTo(used);
        // the automatic release (a cancelled order / failed payment) re-activates it: still inside the set
        int moved = tenants.runAs(t.t(), () -> new TransactionTemplate(txManager).execute(s -> couponRepository.releaseRedemption(exhausted)));
        assertThat(moved).isEqualTo(1);
        assertThat(string("SELECT is_active::text FROM " + t.schema() + ".coupons WHERE id = ?", exhausted)).isEqualTo("true");
        assertThat(count(t, PlanFeature.MAX_ACTIVE_COUPONS)).isEqualTo(used);
        // PUT isActive:true on an already counted coupon is not an entry, never 402
        assertThat(send(t, t.admin(), req(HttpMethod.PUT, "/api/v1/coupon/" + exhausted), couponJson(null, true).replace(
                "\"usageLimit\":10", "\"usageLimit\":10")).getStatus()).isEqualTo(200);
        // an admin deactivate frees the slot
        assertThat(send(t, t.admin(), req(HttpMethod.POST, "/api/v1/coupon/" + exhausted + "/deactivate"), null).getStatus()).isEqualTo(200);
        assertThat(couponCreate(t, true).getStatus()).isEqualTo(200);
        // and re-activating the admin-deactivated one is an entry again
        limitReached(send(t, t.admin(), req(HttpMethod.PUT, "/api/v1/coupon/" + exhausted), couponJson(null, true)),
                PlanFeature.MAX_ACTIVE_COUPONS, (int) used, used);
    }

    private static <R> List<R> parallel(int n, java.util.function.IntFunction<Callable<R>> job) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<R>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<R> c = job.apply(i);
                fs.add(pool.submit(() -> {
                    go.await();
                    return c.call();
                }));
            }
            go.countDown();
            List<R> out = new ArrayList<>();
            for (Future<R> f : fs) {
                out.add(f.get(60, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
