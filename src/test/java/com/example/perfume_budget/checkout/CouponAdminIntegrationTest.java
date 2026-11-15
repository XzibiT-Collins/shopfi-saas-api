package com.example.perfume_budget.checkout;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.example.perfume_budget.support.TestTenant;
import com.example.perfume_budget.support.TestTenants;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * API-04 on the real app + Postgres: coupon validation, normalisation, update / deactivate / delete, authorization
 * (anonymous 401, customer 403, cross-tenant), redemption vs deactivate concurrency, per-user limit, shop-discount
 * current, payment-settings validation and webhook-token rotation.
 */
class CouponAdminIntegrationTest extends AbstractCheckoutIntegrationTest {

    private Cookie adminA;
    private Cookie adminB;

    @BeforeEach
    void admins() throws Exception {
        adminA = cookie("accessToken", setCookieValue(loginPassword("acme", acme.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken"));
        adminB = cookie("accessToken", setCookieValue(loginPassword("bravo", bravo.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken"));
    }

    @AfterEach
    void cleanDiscounts() {
        for (String s : new String[]{"tenant_acme", "tenant_bravo"}) {
            jdbc.update("DELETE FROM " + s + ".shop_wide_discounts");
        }
    }

    // ---- helpers

    private static Map<String, Object> body(String code, String type, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (code != null) m.put("couponCode", code);
        m.put("discountType", type);
        m.put("discountValue", value);
        m.put("maximumDiscountAmount", "100");
        m.put("minimumCartAmountForDiscount", "0");
        m.put("usageLimit", 100);
        m.put("isActive", true);
        m.put("startDate", LocalDate.now().minusDays(1).toString());
        m.put("expirationDate", LocalDate.now().plusDays(30).toString());
        return m;
    }

    private MvcResult send(MockHttpServletRequestBuilder b, TestTenant t, Cookie c, Object content) throws Exception {
        b.header("X-Tenant-ID", t.slug());
        if (c != null) b.cookie(c);
        if (content != null) b.contentType("application/json").content(json.writeValueAsString(content));
        return mvc.perform(b).andReturn();
    }

    private JsonNode data(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private long createCoupon(TestTenant t, Cookie admin, Map<String, Object> b) throws Exception {
        MvcResult r = send(post("/api/v1/coupon/add-coupon"), t, admin, b);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return data(r).at("/data/couponId").asLong();
    }

    private Customer ready(TestTenant t, String email, long productId) {
        Customer c = customer(t, email);
        cart(c, productId, 1);
        return c;
    }

    private int usageCount(String code) {
        return jdbc.queryForObject("SELECT usage_count FROM tenant_acme.coupons WHERE code = ?", Integer.class, code);
    }

    // ---- validation

    @Test
    void invalidRequestsAre400AndNothingIsStored() throws Exception {
        List<Map<String, Object>> bad = new ArrayList<>();
        Map<String, Object> m;
        m = body("BAD1", "FLAT", "-5"); bad.add(m);
        m = body("BAD2", "FLAT", "0"); bad.add(m);
        m = body("BAD3", "PERCENTAGE", "100.5"); bad.add(m);
        m = body("BAD4", "FLAT", "5"); m.put("expirationDate", LocalDate.now().minusDays(5).toString()); bad.add(m);
        m = body("BAD5", "FLAT", "5"); m.put("usageLimit", 0); bad.add(m);
        m = body("BAD6", "FLAT", "5"); m.put("usageLimit", -3); bad.add(m);
        m = body("   ", "FLAT", "5"); bad.add(m);
        m = body("", "FLAT", "5"); bad.add(m);
        m = body("a".repeat(40), "FLAT", "5"); bad.add(m);
        m = body("BAD7", "FLAT", "5"); m.put("perUserUsageLimit", 0); bad.add(m);
        m = body("BAD8", "FLAT", "5"); m.put("perUserUsageLimit", 101); bad.add(m);
        for (Map<String, Object> b : bad) {
            MvcResult r = send(post("/api/v1/coupon/add-coupon"), acme, adminA, b);
            assertThat(r.getResponse().getStatus()).as(b.toString()).isEqualTo(400);
        }
        assertThat(count(acme, "coupons")).isZero();
        // unknown enum / malformed date are 400 too, never 500
        m = body("OKAY1", "BOGUS", "5");
        assertThat(send(post("/api/v1/coupon/add-coupon"), acme, adminA, m).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void createNormalisesCodeAndItRedeemsAtCheckoutWhateverTheCase() throws Exception {
        long p = product(acme, "Rose", "100.00", 20);
        MvcResult r = send(post("/api/v1/coupon/add-coupon"), acme, adminA, body("  save20 ", "PERCENTAGE", "20"));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(data(r).at("/data/couponCode").asText()).isEqualTo("SAVE20");
        assertThat(jdbc.queryForObject("SELECT code FROM tenant_acme.coupons", String.class)).isEqualTo("SAVE20");

        Customer c = ready(acme, "n1@acme.test", p);
        MvcResult co = checkout(c, null, "couponCode=save20");
        assertThat(co.getResponse().getStatus()).as(co.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(usageCount("SAVE20")).isEqualTo(1);
    }

    @Test
    void duplicateCodeIsA409WithFixedCode_caseInsensitively() throws Exception {
        createCoupon(acme, adminA, body("DUPE10", "FLAT", "5"));
        for (String again : new String[]{"DUPE10", "dupe10", " Dupe10 "}) {
            MvcResult r = send(post("/api/v1/coupon/add-coupon"), acme, adminA, body(again, "FLAT", "5"));
            assertThat(r.getResponse().getStatus()).as(again).isEqualTo(409);
            assertThat(data(r).at("/data/code").asText()).isEqualTo("COUPON_CODE_EXISTS");
            assertThat(data(r).get("description").asText()).isEqualTo("A coupon with this code already exists.");
        }
        assertThat(count(acme, "coupons")).isEqualTo(1);
        // the same code in another tenant is fine
        createCoupon(bravo, adminB, body("DUPE10", "FLAT", "5"));
    }

    @Test
    void concurrentDuplicateCreatesYieldOne201AndTheRest409() throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(6);
        try {
            List<Future<Integer>> fs = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                fs.add(ex.submit(() -> send(post("/api/v1/coupon/add-coupon"), acme, adminA, body("RACE10", "FLAT", "5")).getResponse().getStatus()));
            }
            int ok = 0, conflict = 0;
            for (Future<Integer> f : fs) {
                int s = f.get(30, TimeUnit.SECONDS);
                if (s == 200) ok++; else if (s == 409) conflict++; else throw new AssertionError("unexpected " + s);
            }
            assertThat(ok).isEqualTo(1);
            assertThat(conflict).isEqualTo(5);
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void perUserLimitIsSettable_returnedInDetailAndList_andFlatCapIsNull() throws Exception {
        Map<String, Object> b = body("PERUSER", "FLAT", "5");
        b.put("perUserUsageLimit", 2);
        long id = createCoupon(acme, adminA, b);
        JsonNode d = data(send(get("/api/v1/coupon/" + id), acme, adminA, null)).get("data");
        assertThat(d.get("perUserUsageLimit").asInt()).isEqualTo(2);
        assertThat(d.get("maximumDiscountAmount").isNull()).as("FLAT stores no cap").isTrue();

        JsonNode item = data(send(get("/api/v1/coupon/all"), acme, adminA, null)).at("/data/content/0");
        assertThat(item.get("couponCode").asText()).isEqualTo("PERUSER");
        assertThat(item.get("discountType").asText()).isEqualTo("FLAT");
        assertThat(item.get("discountValue").decimalValue()).isEqualByComparingTo("5");
        assertThat(item.get("perUserUsageLimit").asInt()).isEqualTo(2);
        assertThat(item.get("startDate").asText()).isEqualTo(LocalDate.now().minusDays(1).toString());
        assertThat(item.has("minimumCartAmountForDiscount")).isTrue();
        assertThat(item.has("maximumDiscountAmount")).isTrue();
    }

    @Test
    void perUserLimitIsEnforcedAtCheckoutAndPreview_perCustomer() throws Exception {
        long p = product(acme, "Iris", "100.00", 30);
        Map<String, Object> b = body("ONCE", "FLAT", "10");
        b.put("perUserUsageLimit", 1);
        createCoupon(acme, adminA, b);

        Customer c = ready(acme, "pu1@acme.test", p);
        assertThat(checkout(c, "key-pu-0001-aaaa", "couponCode=ONCE").getResponse().getStatus()).isEqualTo(200);
        jdbc.update("DELETE FROM tenant_acme.cart_items");
        cart(c, p, 1);
        MvcResult second = checkout(c, "key-pu-0002-aaaa", "couponCode=ONCE");
        assertThat(second.getResponse().getStatus()).isEqualTo(400);
        assertThat(usageCount("ONCE")).isEqualTo(1);

        MvcResult preview = mvc.perform(as(post("/api/v1/cart/preview").contentType("application/json")
                .content("{\"couponCode\":\"ONCE\"}"), c)).andReturn();
        assertThat(body(preview).at("/data/coupon/valid").asBoolean()).isFalse();

        Customer other = ready(acme, "pu2@acme.test", p);
        assertThat(checkout(other, "key-pu-0003-aaaa", "couponCode=ONCE").getResponse().getStatus()).isEqualTo(200);
        assertThat(usageCount("ONCE")).isEqualTo(2);
    }

    @Test
    void cancelledOrFailedOrdersDoNotConsumeThePerUserLimit() throws Exception {
        long p = product(acme, "Jasmine", "100.00", 30);
        Map<String, Object> b = body("AGAIN", "FLAT", "10");
        b.put("perUserUsageLimit", 1);
        createCoupon(acme, adminA, b);
        Customer c = ready(acme, "pc1@acme.test", p);

        JsonNode first = body(checkout(c, "key-pc-0001-aaaa", "couponCode=AGAIN")).get("data");
        assertThat(cancel(c, first.get("orderNumber").asText()).getResponse().getStatus()).isEqualTo(200);
        jdbc.update("DELETE FROM tenant_acme.cart_items");
        cart(c, p, 1);
        JsonNode second = body(checkout(c, "key-pc-0002-aaaa", "couponCode=AGAIN")).get("data");
        assertThat(second.get("orderNumber").asText()).isNotEqualTo(first.get("orderNumber").asText());

        // a FAILED payment frees the slot as well
        jdbc.update("UPDATE tenant_acme.orders SET status = 'FAILED' WHERE order_number = ?", second.get("orderNumber").asText());
        jdbc.update("DELETE FROM tenant_acme.cart_items");
        cart(c, p, 1);
        assertThat(checkout(c, "key-pc-0003-aaaa", "couponCode=AGAIN").getResponse().getStatus()).isEqualTo(200);
        // ...but a live (pending) order holds it
        jdbc.update("DELETE FROM tenant_acme.cart_items");
        cart(c, p, 1);
        assertThat(checkout(c, "key-pc-0004-aaaa", "couponCode=AGAIN").getResponse().getStatus()).isEqualTo(400);
    }

    // ---- update / deactivate / delete

    @Test
    void updateReplacesFieldsKeepsCodeAndCounter_validatesAgain() throws Exception {
        long id = createCoupon(acme, adminA, body("EDIT10", "PERCENTAGE", "10"));
        Map<String, Object> upd = body("edit10", "FLAT", "7.50");
        upd.put("usageLimit", 5);
        upd.put("perUserUsageLimit", 1);
        MvcResult r = send(put("/api/v1/coupon/" + id), acme, adminA, upd);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode d = data(r).get("data");
        assertThat(d.get("couponCode").asText()).isEqualTo("EDIT10");
        assertThat(d.get("discountType").asText()).isEqualTo("FLAT");
        assertThat(d.get("discountValue").decimalValue()).isEqualByComparingTo("7.50");
        assertThat(d.get("usageLimit").asInt()).isEqualTo(5);

        Map<String, Object> bad = body("EDIT10", "PERCENTAGE", "150");
        assertThat(send(put("/api/v1/coupon/" + id), acme, adminA, bad).getResponse().getStatus()).isEqualTo(400);
        assertThat(send(put("/api/v1/coupon/" + id), acme, adminA, body("OTHERCODE", "FLAT", "5")).getResponse().getStatus()).isEqualTo(400);
        assertThat(send(put("/api/v1/coupon/999999"), acme, adminA, body("EDIT10", "FLAT", "5")).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void updateCannotLowerUsageLimitBelowRedemptions() throws Exception {
        long p = product(acme, "Lily", "100.00", 30);
        long id = createCoupon(acme, adminA, body("LOWER", "FLAT", "5"));
        assertThat(checkout(ready(acme, "lw1@acme.test", p), null, "couponCode=LOWER").getResponse().getStatus()).isEqualTo(200);
        assertThat(checkout(ready(acme, "lw2@acme.test", p), null, "couponCode=LOWER").getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> upd = body("LOWER", "FLAT", "5");
        upd.put("usageLimit", 1);
        assertThat(send(put("/api/v1/coupon/" + id), acme, adminA, upd).getResponse().getStatus()).isEqualTo(400);
        upd.put("usageLimit", 2);
        assertThat(send(put("/api/v1/coupon/" + id), acme, adminA, upd).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void deactivateStopsRedemptionImmediatelyAndPreviewShowsInvalid_orderKeepsItsDiscount() throws Exception {
        long p = product(acme, "Tulip", "100.00", 30);
        long id = createCoupon(acme, adminA, body("LEAKED", "PERCENTAGE", "50"));
        Customer c1 = ready(acme, "dz1@acme.test", p);
        MvcResult first = checkout(c1, null, "couponCode=LEAKED");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);

        MvcResult d = send(post("/api/v1/coupon/" + id + "/deactivate"), acme, adminA, null);
        assertThat(d.getResponse().getStatus()).isEqualTo(200);
        assertThat(data(d).at("/data/isActive").asBoolean()).isFalse();
        // idempotent
        assertThat(send(post("/api/v1/coupon/" + id + "/deactivate"), acme, adminA, null).getResponse().getStatus()).isEqualTo(200);

        Customer c2 = ready(acme, "dz2@acme.test", p);
        assertThat(checkout(c2, null, "couponCode=LEAKED").getResponse().getStatus()).isEqualTo(400);
        assertThat(usageCount("LEAKED")).isEqualTo(1);
        MvcResult pv = mvc.perform(as(post("/api/v1/cart/preview").contentType("application/json").content("{\"couponCode\":\"leaked\"}"), c2)).andReturn();
        assertThat(body(pv).at("/data/coupon/valid").asBoolean()).isFalse();
        assertThat(body(pv).at("/data/coupon/reason").asText()).isEqualTo("COUPON_INVALID");
        // the redeemed order is untouched
        assertThat(jdbc.queryForObject("SELECT discount_amount FROM tenant_acme.orders WHERE coupon_id = ?", java.math.BigDecimal.class, id))
                .isEqualByComparingTo("50");
    }

    @Test
    void deleteOfNeverRedeemedCouponIs204_redeemedIs409AndSurvives() throws Exception {
        long p = product(acme, "Peony", "100.00", 30);
        long unused = createCoupon(acme, adminA, body("UNUSED1", "FLAT", "5"));
        long used = createCoupon(acme, adminA, body("USED1", "FLAT", "5"));
        assertThat(checkout(ready(acme, "dl1@acme.test", p), null, "couponCode=USED1").getResponse().getStatus()).isEqualTo(200);

        assertThat(send(delete("/api/v1/coupon/" + unused), acme, adminA, null).getResponse().getStatus()).isEqualTo(204);
        assertThat(send(get("/api/v1/coupon/" + unused), acme, adminA, null).getResponse().getStatus()).isEqualTo(404);
        assertThat(send(delete("/api/v1/coupon/" + unused), acme, adminA, null).getResponse().getStatus()).isEqualTo(404);

        MvcResult r = send(delete("/api/v1/coupon/" + used), acme, adminA, null);
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(data(r).at("/data/code").asText()).isEqualTo("COUPON_REDEEMED");
        assertThat(send(get("/api/v1/coupon/" + used), acme, adminA, null).getResponse().getStatus()).isEqualTo(200);
        // and a deleted code can be created again
        createCoupon(acme, adminA, body("UNUSED1", "FLAT", "5"));
    }

    // ---- authorization

    @Test
    void anonymousIs401_customerIs403_foreignAdminCannotTouchCoupons() throws Exception {
        long id = createCoupon(acme, adminA, body("AUTHZ1", "FLAT", "5"));
        Customer c = customer(acme, "az@acme.test");
        Cookie cust = cookie("accessToken", c.token());
        Object[][] routes = {
                {"POST", "/api/v1/coupon/add-coupon", body("AUTHZ2", "FLAT", "5")},
                {"GET", "/api/v1/coupon/" + id, null},
                {"GET", "/api/v1/coupon/all", null},
                {"PUT", "/api/v1/coupon/" + id, body("AUTHZ1", "FLAT", "9")},
                {"POST", "/api/v1/coupon/" + id + "/deactivate", null},
                {"DELETE", "/api/v1/coupon/" + id, null},
                {"POST", "/api/v1/payment-settings/rotate-webhook-token", null},
                {"PUT", "/api/v1/payment-settings", Map.of("secretKey", "sk_test_FAKEabcdef1234", "publicKey", "pk_test_FAKEabcdef1234", "enabled", true)},
        };
        for (Object[] rt : routes) {
            assertThat(send(req((String) rt[0], (String) rt[1]), acme, null, rt[2]).getResponse().getStatus())
                    .as("anon %s %s", rt[0], rt[1]).isEqualTo(401);
            assertThat(send(req((String) rt[0], (String) rt[1]), acme, cust, rt[2]).getResponse().getStatus())
                    .as("customer %s %s", rt[0], rt[1]).isEqualTo(403);
            // bravo's admin token presented on acme: AUTH-01 ignores it -> anonymous
            assertThat(send(req((String) rt[0], (String) rt[1]), acme, adminB, rt[2]).getResponse().getStatus())
                    .as("foreign admin %s %s", rt[0], rt[1]).isEqualTo(401);
        }
        // nothing changed
        assertThat(jdbc.queryForObject("SELECT is_active FROM tenant_acme.coupons WHERE id = ?", Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT discount_value FROM tenant_acme.coupons WHERE id = ?", java.math.BigDecimal.class, id)).isEqualByComparingTo("5");
        // same-id coupon id in the other tenant: bravo's admin cannot see / modify / delete acme's coupon
        assertThat(count(bravo, "coupons")).isZero();
        assertThat(send(get("/api/v1/coupon/" + id), bravo, adminB, null).getResponse().getStatus()).isEqualTo(404);
        assertThat(send(put("/api/v1/coupon/" + id), bravo, adminB, body("AUTHZ1", "FLAT", "9")).getResponse().getStatus()).isEqualTo(404);
        assertThat(send(post("/api/v1/coupon/" + id + "/deactivate"), bravo, adminB, null).getResponse().getStatus()).isEqualTo(404);
        assertThat(send(delete("/api/v1/coupon/" + id), bravo, adminB, null).getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT is_active FROM tenant_acme.coupons WHERE id = ?", Boolean.class, id)).isTrue();
        assertThat(data(send(get("/api/v1/coupon/all"), bravo, adminB, null)).at("/data/content").size()).isZero();
    }

    private static MockHttpServletRequestBuilder req(String method, String path) {
        return request(org.springframework.http.HttpMethod.valueOf(method), path);
    }

    // ---- concurrency

    @Test
    void checkoutWaitingOnTheCouponLockSeesADeactivateThatCommitsFirst() throws Exception {
        long p = product(acme, "Aster", "100.00", 30);
        createCoupon(acme, adminA, body("INFLIGHT", "FLAT", "5"));
        Customer c = ready(acme, "if1@acme.test", p);

        ExecutorService ex = Executors.newSingleThreadExecutor();
        try (Connection conn = jdbc.getDataSource().getConnection()) {
            conn.setAutoCommit(false);
            try (var st = conn.createStatement()) {
                st.execute("SELECT id FROM tenant_acme.coupons WHERE code = 'INFLIGHT' FOR UPDATE");
                Future<MvcResult> f = ex.submit(() -> checkout(c, null, "couponCode=INFLIGHT"));
                // wait until the checkout transaction is blocked on the row lock
                long deadline = System.currentTimeMillis() + 20_000;
                while (System.currentTimeMillis() < deadline
                        && jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Integer.class) == 0) {
                    Thread.sleep(50);
                }
                assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Integer.class))
                        .as("checkout is blocked on the coupon row").isPositive();
                assertThat(f.isDone()).isFalse();
                st.execute("UPDATE tenant_acme.coupons SET is_active = false WHERE code = 'INFLIGHT'");
                conn.commit();
                MvcResult r = f.get(30, TimeUnit.SECONDS);
                assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(400);
            }
        } finally {
            ex.shutdownNow();
        }
        assertThat(count(acme, "orders")).isZero();
        assertThat(usageCount("INFLIGHT")).isZero();
    }

    @Test
    void usageLimitHoldsUnderConcurrentCheckouts_counterEqualsOrders() throws Exception {
        Map<String, Object> b = body("LIMIT3", "FLAT", "5");
        b.put("usageLimit", 3);
        createCoupon(acme, adminA, b);
        // one product per customer: the coupon is the only shared row (same-product contention is API-05, see below)
        List<Customer> cs = new ArrayList<>();
        for (int i = 0; i < 8; i++) cs.add(ready(acme, "lim" + i + "@acme.test", product(acme, "Orchid" + i, "100.00", 100)));

        List<Integer> statuses = runConcurrently(cs, "LIMIT3");

        
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(3);
        assertThat(statuses.stream().filter(s -> s == 400).count()).isEqualTo(5);
        assertThat(usageCount("LIMIT3")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.orders WHERE coupon_id IS NOT NULL", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT is_active FROM tenant_acme.coupons WHERE code='LIMIT3'", Boolean.class)).isFalse();
    }

    @Test
    void afterDeactivateReturnsNoCheckoutRedeems_counterMatchesOrdersWhateverTheInterleaving() throws Exception {
        long p = product(acme, "Daisy", "100.00", 100);
        long id = createCoupon(acme, adminA, body("RACEDZ", "FLAT", "5"));
        List<Customer> cs = new ArrayList<>();
        for (int i = 0; i < 8; i++) cs.add(ready(acme, "rd" + i + "@acme.test", product(acme, "Daisy" + i, "100.00", 100)));

        ExecutorService ex = Executors.newFixedThreadPool(9);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> fs = new ArrayList<>();
            for (Customer c : cs) {
                fs.add(ex.submit(() -> { go.await(); return checkout(c, null, "couponCode=racedz").getResponse().getStatus(); }));
            }
            Future<Integer> deact = ex.submit(() -> { go.await(); return send(post("/api/v1/coupon/" + id + "/deactivate"), acme, adminA, null).getResponse().getStatus(); });
            go.countDown();
            assertThat(deact.get(60, TimeUnit.SECONDS)).isEqualTo(200);
            for (Future<Integer> f : fs) assertThat(f.get(60, TimeUnit.SECONDS)).isIn(200, 400);
        } finally {
            ex.shutdownNow();
        }
        int orders = jdbc.queryForObject("SELECT count(*) FROM tenant_acme.orders WHERE coupon_id = ?", Integer.class, id);
        assertThat(usageCount("RACEDZ")).isEqualTo(orders);
        // deactivate has returned: a fresh checkout is rejected
        Customer late = ready(acme, "late@acme.test", p);
        assertThat(checkout(late, null, "couponCode=RACEDZ").getResponse().getStatus()).isEqualTo(400);
        assertThat(usageCount("RACEDZ")).isEqualTo(orders);
    }

    private List<Integer> runConcurrently(List<Customer> cs, String code) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(cs.size());
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> fs = new ArrayList<>();
            for (Customer c : cs) {
                fs.add(ex.submit(() -> { go.await(); return checkout(c, null, "couponCode=" + code).getResponse().getStatus(); }));
            }
            go.countDown();
            List<Integer> out = new ArrayList<>();
            for (Future<Integer> f : fs) out.add(f.get(60, TimeUnit.SECONDS));
            return out;
        } finally {
            ex.shutdownNow();
        }
    }

    // ---- shop discount

    @Test
    void shopDiscountCurrentIgnoresExpiredAndNotYetStartedRows_andBoundsTheLabel() throws Exception {
        jdbc.update("INSERT INTO tenant_acme.shop_wide_discounts (label, discount_percentage, start_at, end_at, is_active, created_at) "
                + "VALUES ('Old', 10, now() - interval '10 days', now() - interval '1 day', true, now())");
        assertThat(send(get("/api/v1/admin/shop-discount/current"), acme, adminA, null).getResponse().getStatus()).isEqualTo(404);
        jdbc.update("INSERT INTO tenant_acme.shop_wide_discounts (label, discount_percentage, start_at, end_at, is_active, created_at) "
                + "VALUES ('Future', 10, now() + interval '1 day', now() + interval '10 days', true, now())");
        assertThat(send(get("/api/v1/admin/shop-discount/current"), acme, adminA, null).getResponse().getStatus()).isEqualTo(404);
        jdbc.update("INSERT INTO tenant_acme.shop_wide_discounts (label, discount_percentage, start_at, end_at, is_active, created_at) "
                + "VALUES ('Now', 15, now() - interval '1 day', now() + interval '1 day', true, now())");
        MvcResult r = send(get("/api/v1/admin/shop-discount/current"), acme, adminA, null);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(data(r).at("/data/label").asText()).isEqualTo("Now");
        assertThat(data(r).at("/data/currentlyActive").asBoolean()).isTrue();

        Map<String, Object> put = Map.of("label", "x".repeat(101), "discountPercentage", 10,
                "startAt", "2030-01-01T00:00:00", "endAt", "2030-02-01T00:00:00");
        assertThat(send(put("/api/v1/admin/shop-discount"), acme, adminA, put).getResponse().getStatus()).isEqualTo(400);
    }

    // ---- payment settings

    @Test
    void paymentSettingsRejectBadKeysWithoutEchoingThem_andDoNotStoreThem() throws Exception {
        String secret = "sk_test_NOTVALID-has-dash-" + "z".repeat(8);
        MvcResult r = send(put("/api/v1/payment-settings"), acme, adminA,
                Map.of("secretKey", secret, "publicKey", "pk_test_FAKEabcdef1234", "enabled", true));
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).doesNotContain(secret).doesNotContain("NOTVALID");
        assertThat(count(acme, "payment_gateway_config")).isZero();

        MvcResult mixed = send(put("/api/v1/payment-settings"), acme, adminA,
                Map.of("secretKey", "sk_live_ABCDEFGH12345", "publicKey", "pk_test_FAKEabcdef1234", "enabled", true));
        assertThat(mixed.getResponse().getStatus()).isEqualTo(400);
        assertThat(count(acme, "payment_gateway_config")).isZero();
    }

    @Test
    void rotateWebhookTokenKillsTheOldUrlAndTheNewOneWorks_noSecretsInResponsesOrLogs() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        try {
            String secret = "sk_test_FAKEROTATE" + java.util.UUID.randomUUID().toString().replace("-", "");
            MvcResult saved = send(put("/api/v1/payment-settings"), acme, adminA,
                    Map.of("secretKey", secret, "publicKey", "pk_test_FAKEROTATEpub", "enabled", true));
            assertThat(saved.getResponse().getStatus()).isEqualTo(200);
            String oldUrl = data(saved).at("/data/webhookUrl").asText();
            String oldToken = oldUrl.substring(oldUrl.lastIndexOf('/') + 1);
            Gateway oldG = new Gateway(oldToken, secret);
            String evt = event("charge.failed", "REF-NONE", 100);
            assertThat(webhook(oldG, evt)).as("old token resolves before rotation").isNotEqualTo(404);

            MvcResult rot = send(post("/api/v1/payment-settings/rotate-webhook-token"), acme, adminA, null);
            assertThat(rot.getResponse().getStatus()).isEqualTo(200);
            String newUrl = data(rot).at("/data/webhookUrl").asText();
            String newToken = newUrl.substring(newUrl.lastIndexOf('/') + 1);
            assertThat(newToken).isNotEqualTo(oldToken).hasSize(43);
            assertThat(data(rot).at("/data/secretConfigured").asBoolean()).isTrue();
            assertThat(data(rot).at("/data/publicKey").asText()).isEqualTo("pk_test_FAKEROTATEpub");
            assertThat(rot.getResponse().getContentAsString()).doesNotContain(secret).doesNotContain("secretKey");

            assertThat(webhook(oldG, evt)).as("old token is dead at once").isEqualTo(404);
            assertThat(webhook(new Gateway(newToken, secret), evt)).as("new token resolves").isNotEqualTo(404);
            // GET reflects the new URL and still never returns the secret
            MvcResult got = send(get("/api/v1/payment-settings"), acme, adminA, null);
            assertThat(data(got).at("/data/webhookUrl").asText()).isEqualTo(newUrl);
            assertThat(got.getResponse().getContentAsString()).doesNotContain(secret);
            // the secret itself is unchanged by rotation (decrypts: signature with it still verifies, above) and the stored value is not plaintext
            assertThat(jdbc.queryForObject("SELECT secret_key_encrypted FROM tenant_acme.payment_gateway_config", String.class)).doesNotContain(secret);

            // another tenant's token is untouched by acme's rotation
            Gateway b = configureGateway(bravo);
            send(post("/api/v1/payment-settings/rotate-webhook-token"), acme, adminA, null);
            assertThat(webhook(b, evt)).isNotEqualTo(404);

            for (ILoggingEvent e : logs.list) {
                String line = e.getFormattedMessage();
                assertThat(line).as("log line leaks").doesNotContain(secret).doesNotContain(oldToken).doesNotContain(newToken);
            }
        } finally {
            root.detachAppender(logs);
        }
    }

    @Test
    void rotateOnATenantWithoutPriorSettingsStillMintsAToken_andRotateTwiceDiffers() throws Exception {
        MvcResult r1 = send(post("/api/v1/payment-settings/rotate-webhook-token"), acme, adminA, null);
        assertThat(r1.getResponse().getStatus()).isEqualTo(200);
        assertThat(data(r1).at("/data/secretConfigured").asBoolean()).isFalse();
        String u1 = data(r1).at("/data/webhookUrl").asText();
        String u2 = data(send(post("/api/v1/payment-settings/rotate-webhook-token"), acme, adminA, null)).at("/data/webhookUrl").asText();
        assertThat(u1).contains("/api/v1/webhook/").isNotEqualTo(u2);
    }
}
