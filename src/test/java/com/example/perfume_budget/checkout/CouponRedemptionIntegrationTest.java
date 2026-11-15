package com.example.perfume_budget.checkout;

import com.example.perfume_budget.dto.payment.response.PaystackInitiateTransactionResponse;
import com.example.perfume_budget.support.TestTenants;
import com.example.perfume_budget.task.ReservedProductCleanupTask;
import jakarta.servlet.http.Cookie;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * API-04 rework on the real app + Postgres: the coupon row is never locked across the Paystack call (atomic
 * reservation in its own transaction), redemptions are released exactly once on cancel / failure / expiry and taken
 * again on a late-payment revival, COUPON_REDEEMED counts live redemptions only, lock waits are bounded (503), legacy
 * codes are normalised by V12, the per-user limit applies to GET /tax and the webhook token rotation is serialised.
 */
class CouponRedemptionIntegrationTest extends AbstractCheckoutIntegrationTest {

    @Autowired ReservedProductCleanupTask cleanupTask;
    @Autowired javax.sql.DataSource dataSource;

    private Cookie adminA;

    @BeforeEach
    void admin() throws Exception {
        adminA = cookie("accessToken", setCookieValue(loginPassword("acme", acme.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken"));
    }

    // ---- helpers

    private static Map<String, Object> body(String code, int usageLimit, Integer perUser) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("couponCode", code);
        m.put("discountType", "FLAT");
        m.put("discountValue", "5");
        m.put("minimumCartAmountForDiscount", "0");
        m.put("usageLimit", usageLimit);
        if (perUser != null) m.put("perUserUsageLimit", perUser);
        m.put("isActive", true);
        m.put("startDate", LocalDate.now().minusDays(1).toString());
        m.put("expirationDate", LocalDate.now().plusDays(30).toString());
        return m;
    }

    private MvcResult send(MockHttpServletRequestBuilder b, Object content) throws Exception {
        b.header("X-Tenant-ID", acme.slug()).cookie(adminA);
        if (content != null) b.contentType("application/json").content(json.writeValueAsString(content));
        return mvc.perform(b).andReturn();
    }

    private long createCoupon(Map<String, Object> b) throws Exception {
        MvcResult r = send(post("/api/v1/coupon/add-coupon"), b);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r).at("/data/couponId").asLong();
    }

    private Customer ready(String email) {
        Customer c = customer(acme, email);
        cart(c, product(acme, "P " + email, "100.00", 50), 1);
        return c;
    }

    private int usage(String code) {
        return jdbc.queryForObject("SELECT usage_count FROM tenant_acme.coupons WHERE code = ?", Integer.class, code);
    }

    private boolean active(String code) {
        return jdbc.queryForObject("SELECT is_active FROM tenant_acme.coupons WHERE code = ?", Boolean.class, code);
    }

    private String place(Customer c, String code) throws Exception {
        MvcResult r = checkout(c, null, "couponCode=" + code);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r).at("/data/orderNumber").asText();
    }

    private String reference(String orderNumber) {
        return jdbc.queryForObject("SELECT p.provider_payment_reference FROM tenant_acme.payments p JOIN tenant_acme.orders o "
                + "ON o.id = p.order_id WHERE o.order_number = ?", String.class, orderNumber);
    }

    private long totalMinor(String orderNumber) {
        return jdbc.queryForObject("SELECT total_amount * 100 FROM tenant_acme.orders WHERE order_number = ?", BigDecimal.class, orderNumber).longValue();
    }

    private int blockedLocks() {
        return jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Integer.class);
    }

    // ---- HIGH: no coupon lock across the Paystack call

    @Test
    void checkoutStuckInASlowPaystackCallBlocksNeitherAnotherCheckoutNorADeactivateOfTheSameCoupon() throws Exception {
        long id = createCoupon(body("SLOWPAY", 10, null));
        Customer slow = ready("slow@acme.test");
        Customer fast = ready("fast@acme.test");
        CountDownLatch inPaystack = new CountDownLatch(1);
        CountDownLatch releasePaystack = new CountDownLatch(1);
        when(gateway.initiatePaystackTransaction(any())).thenAnswer(inv -> {
            initiateCalls.incrementAndGet();
            String email = ((com.example.perfume_budget.dto.payment.request.PaystackInitiatePaymentRequest) inv.getArgument(0)).email();
            if (email.equals(slow.email())) {
                inPaystack.countDown();
                releasePaystack.await(60, TimeUnit.SECONDS); // the "15 s" Paystack call, held open by the test
            }
            String code = java.util.UUID.randomUUID().toString().substring(0, 8);
            return new PaystackInitiateTransactionResponse(true, "Authorization URL created",
                    new PaystackInitiateTransactionResponse.Data("https://checkout.paystack.com/" + code, code, "T" + code));
        });

        ExecutorService ex = Executors.newFixedThreadPool(3);
        try {
            Future<MvcResult> stuck = ex.submit(() -> checkout(slow, null, "couponCode=slowpay"));
            assertThat(inPaystack.await(30, TimeUnit.SECONDS)).as("first checkout reached Paystack").isTrue();

            // another customer redeems the same coupon while the first is inside Paystack
            MvcResult other = ex.submit(() -> checkout(fast, null, "couponCode=SLOWPAY")).get(10, TimeUnit.SECONDS);
            assertThat(other.getResponse().getStatus()).as(other.getResponse().getContentAsString()).isEqualTo(200);
            // and the admin can revoke it at once
            MvcResult deact = ex.submit(() -> send(post("/api/v1/coupon/" + id + "/deactivate"), null)).get(10, TimeUnit.SECONDS);
            assertThat(deact.getResponse().getStatus()).isEqualTo(200);
            assertThat(stuck.isDone()).as("first checkout still inside Paystack").isFalse();
            // even a full edit does not wait
            Map<String, Object> edit = body("SLOWPAY", 20, null);
            edit.put("isActive", false);
            MvcResult put = ex.submit(() -> send(put("/api/v1/coupon/" + id), edit)).get(10, TimeUnit.SECONDS);
            assertThat(put.getResponse().getStatus()).as(put.getResponse().getContentAsString()).isEqualTo(200);

            releasePaystack.countDown();
            // it reserved before the deactivate committed, so it completes with the discount
            MvcResult first = stuck.get(30, TimeUnit.SECONDS);
            assertThat(first.getResponse().getStatus()).as(first.getResponse().getContentAsString()).isEqualTo(200);
        } finally {
            releasePaystack.countDown();
            ex.shutdownNow();
        }
        assertThat(usage("SLOWPAY")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.orders WHERE coupon_id = ?", Integer.class, id)).isEqualTo(2);
        // after the deactivate a new checkout is refused
        assertThat(checkout(ready("late@acme.test"), null, "couponCode=SLOWPAY").getResponse().getStatus()).isEqualTo(400);
        assertThat(usage("SLOWPAY")).isEqualTo(2);
    }

    @Test
    void lowerCaseCodeUnderConcurrentCheckoutsStillHoldsTheExactLimit() throws Exception {
        Map<String, Object> b = body("LOWLIM", 2, null);
        createCoupon(b);
        List<Customer> cs = new ArrayList<>();
        for (int i = 0; i < 7; i++) cs.add(ready("low" + i + "@acme.test"));
        ExecutorService ex = Executors.newFixedThreadPool(cs.size());
        List<Integer> statuses = new ArrayList<>();
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> fs = new ArrayList<>();
            for (int i = 0; i < cs.size(); i++) {
                Customer c = cs.get(i);
                String spelling = i % 2 == 0 ? "lowlim" : " LowLim ";
                fs.add(ex.submit(() -> { go.await(); return checkout(c, null, "couponCode=" + spelling).getResponse().getStatus(); }));
            }
            go.countDown();
            for (Future<Integer> f : fs) statuses.add(f.get(60, TimeUnit.SECONDS));
        } finally {
            ex.shutdownNow();
        }
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(2);
        assertThat(statuses.stream().filter(s -> s == 400).count()).isEqualTo(5);
        assertThat(usage("LOWLIM")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.orders WHERE coupon_id IS NOT NULL", Integer.class)).isEqualTo(2);
        assertThat(active("LOWLIM")).isFalse();
    }

    @Test
    void idempotentReplayOfTheLastRedemptionReplaysAndDoesNotCountTwice() throws Exception {
        createCoupon(body("LASTONE", 1, null));
        Customer c = ready("replay@acme.test");
        MvcResult first = checkout(c, "key-replay-0001", "couponCode=LASTONE");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(usage("LASTONE")).isEqualTo(1);
        MvcResult again = checkout(c, "key-replay-0001", "couponCode=lastone");
        assertThat(again.getResponse().getStatus()).as(again.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(again).at("/data/idempotentReplay").asBoolean()).isTrue();
        assertThat(usage("LASTONE")).isEqualTo(1);
        assertThat(count(acme, "orders")).isEqualTo(1);
    }

    @Test
    void paystackFailureGivesTheReservationBack() throws Exception {
        createCoupon(body("PAYFAIL", 1, null));
        when(gateway.initiatePaystackTransaction(any())).thenThrow(new com.example.perfume_budget.exception.PaymentException("down"));
        MvcResult r = checkout(ready("pf@acme.test"), null, "couponCode=PAYFAIL");
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(usage("PAYFAIL")).isZero();
        assertThat(active("PAYFAIL")).as("the reservation hit the limit, the release re-activated it").isTrue();
    }

    // ---- lock wait timeout

    @Test
    void aCouponRowLockedTooLongIsA503CouponBusy_notA500_andNothingIsCounted() throws Exception {
        long id = createCoupon(body("BUSY1", 10, null));
        Customer c = ready("busy@acme.test");
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try (Connection conn = jdbc.getDataSource().getConnection()) {
            conn.setAutoCommit(false);
            try (var st = conn.createStatement()) {
                st.execute("SELECT id FROM tenant_acme.coupons WHERE code = 'BUSY1' FOR UPDATE");
                Future<MvcResult> co = ex.submit(() -> checkout(c, null, "couponCode=BUSY1"));
                Future<MvcResult> de = ex.submit(() -> send(post("/api/v1/coupon/" + id + "/deactivate"), null));
                MvcResult r = co.get(30, TimeUnit.SECONDS);
                MvcResult d = de.get(30, TimeUnit.SECONDS);
                assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(503);
                assertThat(body(r).at("/data/code").asText()).isEqualTo("COUPON_BUSY");
                assertThat(d.getResponse().getStatus()).as(d.getResponse().getContentAsString()).isEqualTo(503);
                assertThat(body(d).at("/data/code").asText()).isEqualTo("COUPON_BUSY");
                conn.rollback();
            }
        } finally {
            ex.shutdownNow();
        }
        assertThat(usage("BUSY1")).isZero();
        assertThat(active("BUSY1")).isTrue();
        assertThat(count(acme, "orders")).isZero();
    }

    // ---- MEDIUM: release on cancel / failure / expiry

    @Test
    void customerCancelReleasesOnce_reactivatesALimitDeactivatedCoupon_andCheckoutCancelLoopsCannotBurnIt() throws Exception {
        createCoupon(body("ONEUSE", 1, null));
        Customer c = ready("loop@acme.test");
        for (int i = 0; i < 3; i++) {
            String order = place(c, "ONEUSE");
            assertThat(usage("ONEUSE")).isEqualTo(1);
            assertThat(active("ONEUSE")).as("switched off at the limit").isFalse();
            assertThat(cancel(c, order).getResponse().getStatus()).isEqualTo(200);
            assertThat(cancel(c, order).getResponse().getStatus()).as("repeat cancel is idempotent").isEqualTo(200);
            assertThat(usage("ONEUSE")).as("released exactly once").isZero();
            assertThat(active("ONEUSE")).as("re-activated: the limit was the only reason").isTrue();
            jdbc.update("DELETE FROM tenant_acme.cart_items");
            cart(c, product(acme, "Loop" + i, "100.00", 5), 1);
        }
        // another customer can still use it
        place(ready("other@acme.test"), "ONEUSE");
        assertThat(usage("ONEUSE")).isEqualTo(1);
    }

    @Test
    void anAdminDeactivatedCouponIsNotSwitchedBackOnByARelease() throws Exception {
        long id = createCoupon(body("ADMOFF", 1, null));
        Customer c = ready("admoff@acme.test");
        String order = place(c, "ADMOFF");
        assertThat(send(post("/api/v1/coupon/" + id + "/deactivate"), null).getResponse().getStatus()).isEqualTo(200);
        cancel(c, order);
        assertThat(usage("ADMOFF")).isZero();
        assertThat(active("ADMOFF")).isFalse();
    }

    @Test
    void failedPaymentWebhookReleasesOnce_andALatePaymentRevivalTakesItAgain() throws Exception {
        Gateway g = configureGateway(acme);
        createCoupon(body("WEBHK", 5, null));
        String order = place(ready("wh@acme.test"), "WEBHK");
        String ref = reference(order);
        assertThat(usage("WEBHK")).isEqualTo(1);

        webhook(g, event("charge.failed", ref, totalMinor(order)));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(orderStatus(acme, order)).isEqualTo("FAILED"));
        assertThat(usage("WEBHK")).isZero();
        webhook(g, event("charge.failed", ref, totalMinor(order)));
        Thread.sleep(500);
        assertThat(usage("WEBHK")).as("a repeated failure does not release twice").isZero();

        webhook(g, event("charge.success", ref, totalMinor(order)));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(orderStatus(acme, order)).isEqualTo("COMPLETED"));
        assertThat(usage("WEBHK")).as("the revived order holds its redemption again").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT coupon_redemption_released FROM tenant_acme.orders WHERE order_number = ?",
                Boolean.class, order)).isFalse();
    }

    @Test
    void expiryReleasesTheRedemption_andAnOrderWithoutACouponTouchesNoCoupon() throws Exception {
        createCoupon(body("EXPIRE", 5, null));
        String withCoupon = place(ready("ex1@acme.test"), "EXPIRE");
        Customer plain = ready("ex2@acme.test");
        MvcResult r = checkout(plain, null, null);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(usage("EXPIRE")).isEqualTo(1);

        jdbc.update("UPDATE tenant_acme.inventory_allocations SET created_at = now() - interval '5 hours'");
        cleanupTask.cleanupExpiredReservations();
        cleanupTask.cleanupExpiredReservations();

        assertThat(orderStatus(acme, withCoupon)).isEqualTo("CANCELLED");
        assertThat(usage("EXPIRE")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.orders WHERE coupon_redemption_released", Integer.class))
                .as("only the coupon order is marked released").isEqualTo(1);
    }

    @Test
    void couponWhoseOnlyOrderWasCancelledCanBeDeleted_aLiveOneCannot() throws Exception {
        long id = createCoupon(body("GONE1", 5, null));
        Customer c = ready("gone@acme.test");
        String order = place(c, "GONE1");
        MvcResult blocked = send(delete("/api/v1/coupon/" + id), null);
        assertThat(blocked.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(blocked).at("/data/code").asText()).isEqualTo("COUPON_REDEEMED");

        cancel(c, order);
        assertThat(send(delete("/api/v1/coupon/" + id), null).getResponse().getStatus()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT coupon_id FROM tenant_acme.orders WHERE order_number = ?", Long.class, order)).isNull();
        assertThat(jdbc.queryForObject("SELECT discount_amount FROM tenant_acme.orders WHERE order_number = ?", BigDecimal.class, order))
                .isEqualByComparingTo("5");
    }

    // ---- LOW: per-user limit on GET /tax

    @Test
    void taxEndpointAppliesThePerUserLimit() throws Exception {
        createCoupon(body("TAXONCE", 10, 1));
        Customer c = ready("tax1@acme.test");
        MvcResult before = mvc.perform(as(get("/api/v1/tax").param("subtotal", "100").param("couponCode", "taxonce"), c)).andReturn();
        assertThat(before.getResponse().getStatus()).isEqualTo(200);
        place(c, "TAXONCE");
        MvcResult after = mvc.perform(as(get("/api/v1/tax").param("subtotal", "100").param("couponCode", "TAXONCE"), c)).andReturn();
        assertThat(after.getResponse().getStatus()).isEqualTo(400);
        Customer other = ready("tax2@acme.test");
        assertThat(mvc.perform(as(get("/api/v1/tax").param("subtotal", "100").param("couponCode", "TAXONCE"), other))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    // ---- LOW: legacy codes

    @Test
    void legacyLowerCaseCode_putWithItsOwnCodeWorks_andAnUpperCaseTwinIsRefused() throws Exception {
        // a row stored before API-04 normalisation (inserted as the old code did, bypassing the service)
        coupon(acme, "legacy10", 10);
        long id = jdbc.queryForObject("SELECT id FROM tenant_acme.coupons WHERE code = 'legacy10'", Long.class);
        Map<String, Object> b = body("legacy10", 50, null);
        MvcResult put = send(put("/api/v1/coupon/" + id), b);
        assertThat(put.getResponse().getStatus()).as(put.getResponse().getContentAsString()).isEqualTo(200);
        MvcResult twin = send(post("/api/v1/coupon/add-coupon"), body("LEGACY10", 5, null));
        assertThat(twin.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(twin).at("/data/code").asText()).isEqualTo("COUPON_CODE_EXISTS");
    }

    @Test
    void v12NormalisesLegacyCodesAndResolvesCollisionsWithoutLosingRows() {
        String schema = "tenant_v12check";
        jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migrations/tenant").target("11").load().migrate();
            String ins = "INSERT INTO " + schema + ".coupons (code, discount_type, discount_value, is_active, start_date, "
                    + "expiration_date, usage_count, usage_limit, created_at) VALUES (?, 'FLAT', 5, true, current_date, current_date + 1, ?, 10, now())";
            jdbc.update(ins, "save10", 0);      // id 1: lower case, no twin
            jdbc.update(ins, "dup5", 0);        // id 2: collides with 3 and 4
            jdbc.update(ins, "DUP5", 2);        // id 3: already upper case -> wins
            jdbc.update(ins, " Dup5 ", 0);      // id 4
            jdbc.update(ins, "mix", 0);         // id 5: collides with 6, no upper-case row -> lowest id wins
            jdbc.update(ins, "Mix", 0);         // id 6
            jdbc.update(ins, "", 0);            // id 7: blank
            jdbc.update(ins, "legacy-null", null); // id 8: null usage count
            // an order that was cancelled before V12 still counted against its coupon (id 3)
            Long user = jdbc.queryForObject("INSERT INTO " + schema + ".users (full_name, email, password, roles, created_at) "
                    + "VALUES ('U', 'u@x.test', 'x', 'CUSTOMER', now()) RETURNING id", Long.class);
            jdbc.update("INSERT INTO " + schema + ".orders (order_number, user_id, status, delivery_status, coupon_id, created_at, "
                    + "discount_amount) VALUES ('O-1', ?, 'CANCELLED', 'CANCELLED', 3, now(), 5)", user);

            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migrations/tenant").load().migrate();

            Map<Long, Map<String, Object>> rows = new LinkedHashMap<>();
            for (Map<String, Object> r : jdbc.queryForList("SELECT id, code, is_active, usage_count FROM " + schema + ".coupons ORDER BY id")) {
                rows.put(((Number) r.get("id")).longValue(), r);
            }
            assertThat(rows).hasSize(8);
            assertThat(rows.get(1L)).containsEntry("code", "SAVE10").containsEntry("is_active", true);
            assertThat(rows.get(3L)).containsEntry("code", "DUP5").containsEntry("is_active", true).containsEntry("usage_count", 1);
            assertThat(rows.get(2L)).containsEntry("code", "DUP5-DUP-2").containsEntry("is_active", false);
            assertThat(rows.get(4L)).containsEntry("code", "DUP5-DUP-4").containsEntry("is_active", false);
            assertThat(rows.get(5L)).containsEntry("code", "MIX").containsEntry("is_active", true);
            assertThat(rows.get(6L)).containsEntry("code", "MIX-DUP-6").containsEntry("is_active", false);
            assertThat(rows.get(7L)).containsEntry("code", "LEGACY-BLANK-7").containsEntry("is_active", false);
            assertThat(rows.get(8L)).containsEntry("code", "LEGACY-NULL").containsEntry("usage_count", 0);
            assertThat(jdbc.queryForObject("SELECT coupon_redemption_released FROM " + schema + ".orders WHERE order_number = 'O-1'",
                    Boolean.class)).isTrue();
            // case-insensitive uniqueness from now on
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(ins, "save10", 0))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    // ---- LOW: strict integers over HTTP

    @Test
    void coercedOrOverflowingIntegersAreA400WithAFieldMessage() throws Exception {
        for (String[] c : new String[][]{{"1.5", "usageLimit must be a whole number"},
                {"\"7\"", "usageLimit must be a whole number (a JSON number, not text)"},
                {"1E999999999", "usageLimit must be a whole number"},
                {"99999999999", "usageLimit is out of range"}}) {
            String raw = "{\"couponCode\":\"STRICT1\",\"discountType\":\"FLAT\",\"discountValue\":5,\"usageLimit\":" + c[0]
                    + ",\"isActive\":true,\"startDate\":\"" + LocalDate.now() + "\",\"expirationDate\":\"" + LocalDate.now().plusDays(1) + "\"}";
            MvcResult r = mvc.perform(post("/api/v1/coupon/add-coupon").header("X-Tenant-ID", acme.slug()).cookie(adminA)
                    .contentType("application/json").content(raw)).andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(r).at("/description").asText()).isEqualTo(c[1]);
        }
        assertThat(count(acme, "coupons")).isZero();
    }

    // ---- LOW: webhook token rotation is serialised on the tenant row

    @Test
    void rotationWaitsForTheTenantRowLock_boundedBy503() throws Exception {
        configureGateway(acme);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try (Connection conn = jdbc.getDataSource().getConnection()) {
            conn.setAutoCommit(false);
            try (var st = conn.createStatement()) {
                st.execute("SELECT id FROM master.tenants WHERE slug = 'acme' FOR UPDATE");
                Future<MvcResult> f = ex.submit(() -> send(post("/api/v1/payment-settings/rotate-webhook-token"), null));
                long deadline = System.currentTimeMillis() + 3_000;
                while (System.currentTimeMillis() < deadline && blockedLocks() == 0) Thread.sleep(50);
                assertThat(blockedLocks()).as("rotation waits on the tenant row").isPositive();
                MvcResult r = f.get(30, TimeUnit.SECONDS);
                assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(503);
                assertThat(body(r).at("/data/code").asText()).isEqualTo("PAYMENT_SETTINGS_BUSY");
                conn.rollback();
            }
        } finally {
            ex.shutdownNow();
        }
        // once the lock is free it works and the returned URL is the live one
        MvcResult ok = send(post("/api/v1/payment-settings/rotate-webhook-token"), null);
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        String url = body(ok).at("/data/webhookUrl").asText();
        assertThat(url).endsWith("/" + jdbc.queryForObject("SELECT webhook_token FROM master.tenants WHERE slug = 'acme'", String.class));
    }

    @Test
    void concurrentRotationsEachReturnTheTokenTheyCommitted_lastOneIsLive() throws Exception {
        configureGateway(acme);
        ExecutorService ex = Executors.newFixedThreadPool(4);
        List<String> urls = new ArrayList<>();
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<MvcResult>> fs = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                fs.add(ex.submit(() -> { go.await(); return send(post("/api/v1/payment-settings/rotate-webhook-token"), null); }));
            }
            go.countDown();
            for (Future<MvcResult> f : fs) {
                MvcResult r = f.get(30, TimeUnit.SECONDS);
                assertThat(r.getResponse().getStatus()).isEqualTo(200);
                urls.add(body(r).at("/data/webhookUrl").asText());
            }
        } finally {
            ex.shutdownNow();
        }
        assertThat(urls).doesNotHaveDuplicates();
        String live = jdbc.queryForObject("SELECT webhook_token FROM master.tenants WHERE slug = 'acme'", String.class);
        assertThat(urls.stream().filter(u -> u.endsWith("/" + live)).count()).isEqualTo(1);
    }
}
