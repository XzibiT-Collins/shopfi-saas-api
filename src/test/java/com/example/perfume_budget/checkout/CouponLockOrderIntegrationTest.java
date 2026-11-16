package com.example.perfume_budget.checkout;

import com.example.perfume_budget.dto.payment.request.PaystackInitiatePaymentRequest;
import com.example.perfume_budget.dto.payment.response.PaystackInitiateTransactionResponse;
import com.example.perfume_budget.support.TestTenants;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * API-04 rework 2: lock order order -> stock -> coupon, coupon LAST. A checkout holds its stock rows across the
 * Paystack call (ORD-01, unchanged); nothing that changes a coupon counter may hold the coupon row while it waits for
 * those stock rows, and the two paths that used opposite orders (cancel and charge.failed) can no longer deadlock.
 * Probes P1 / P2 of the API-04 re-review, made permanent, plus the revival path, the TRY_AGAIN mapping of a real
 * deadlock and the delete lock order (X17).
 */
class CouponLockOrderIntegrationTest extends AbstractCheckoutIntegrationTest {

    private Cookie adminA;

    @BeforeEach
    void admin() throws Exception {
        adminA = cookie("accessToken", setCookieValue(loginPassword("acme", acme.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken"));
    }

    // ---- helpers

    private static Map<String, Object> body(String code, int usageLimit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("couponCode", code);
        m.put("discountType", "FLAT");
        m.put("discountValue", "5");
        m.put("minimumCartAmountForDiscount", "0");
        m.put("usageLimit", usageLimit);
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

    private int usage(String code) {
        return jdbc.queryForObject("SELECT usage_count FROM tenant_acme.coupons WHERE code = ?", Integer.class, code);
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

    private void awaitBlocked(int atLeast, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline && blockedLocks() < atLeast) {
            Thread.sleep(10);
        }
    }

    private String place(Customer c, String code) throws Exception {
        MvcResult r = checkout(c, null, code == null ? null : "couponCode=" + code);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r).at("/data/orderNumber").asText();
    }

    /** Paystack stub that holds the call of {@code slowEmail} open until {@code release} (ORD-01: stock held). */
    private void slowPaystackFor(String slowEmail, CountDownLatch inPaystack, CountDownLatch release) {
        when(gateway.initiatePaystackTransaction(any())).thenAnswer(inv -> {
            String email = ((PaystackInitiatePaymentRequest) inv.getArgument(0)).email();
            if (email.equals(slowEmail)) {
                inPaystack.countDown();
                release.await(60, TimeUnit.SECONDS);
            }
            String code = UUID.randomUUID().toString().substring(0, 8);
            return new PaystackInitiateTransactionResponse(true, "Authorization URL created",
                    new PaystackInitiateTransactionResponse.Data("https://checkout.paystack.com/" + code, code, "T" + code));
        });
    }

    /** Another customer's coupon checkout (other product) and an admin edit: both must answer at once. */
    private void couponIsNotHeld(ExecutorService ex, String code, long couponId, String who) throws Exception {
        Customer y = customer(acme, who + "@acme.test");
        cart(y, product(acme, "Other " + who, "100.00", 50), 1);
        long t0 = System.nanoTime();
        MvcResult r = ex.submit(() -> checkout(y, null, "couponCode=" + code)).get(30, TimeUnit.SECONDS);
        long elapsed = (System.nanoTime() - t0) / 1_000_000;
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(elapsed).as("the coupon row is not held while waiting for stock").isLessThan(3_000);

        Map<String, Object> edit = body(code, 20);
        edit.put("discountValue", "6");
        t0 = System.nanoTime();
        MvcResult e = ex.submit(() -> send(put("/api/v1/coupon/" + couponId), edit)).get(30, TimeUnit.SECONDS);
        elapsed = (System.nanoTime() - t0) / 1_000_000;
        assertThat(e.getResponse().getStatus()).as(e.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(elapsed).as("an admin edit does not wait either").isLessThan(3_000);
    }

    // ---- P1: charge.failed waiting for stock held by a slow checkout does not hold the coupon

    @Test
    void failedPaymentWebhookWaitingForStockOfASlowCheckoutDoesNotHoldTheCoupon() throws Exception {
        Gateway g = configureGateway(acme);
        long cid = createCoupon(body("HOLD", 10));
        long shared = product(acme, "Shared", "100.00", 50);
        Customer a = customer(acme, "a@acme.test");
        cart(a, shared, 1);
        String oa = place(a, "HOLD");
        String ref = reference(oa);

        Customer x = customer(acme, "x@acme.test");
        cart(x, shared, 1);
        CountDownLatch inPaystack = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        slowPaystackFor(x.email(), inPaystack, release);
        ExecutorService ex = Executors.newFixedThreadPool(3);
        try {
            Future<MvcResult> stuck = ex.submit(() -> checkout(x, null, null));
            assertThat(inPaystack.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(webhook(g, event("charge.failed", ref, totalMinor(oa)))).isEqualTo(200);
            awaitBlocked(1, 5_000);
            assertThat(blockedLocks()).as("the failure webhook waits for the slow checkout's stock").isPositive();

            couponIsNotHeld(ex, "HOLD", cid, "y1");

            release.countDown();
            assertThat(stuck.get(30, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        } finally {
            release.countDown();
            ex.shutdownNow();
        }
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(orderStatus(acme, oa)).isEqualTo("FAILED"));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(usage("HOLD")).as("only y1's redemption").isEqualTo(1));
    }

    // ---- revival: a late payment waiting for stock does not hold the coupon either

    @Test
    void latePaymentRevivalWaitingForStockOfASlowCheckoutDoesNotHoldTheCoupon() throws Exception {
        Gateway g = configureGateway(acme);
        long cid = createCoupon(body("REVIVE", 10));
        long shared = product(acme, "SharedR", "100.00", 50);
        Customer a = customer(acme, "ra@acme.test");
        cart(a, shared, 1);
        String oa = place(a, "REVIVE");
        String ref = reference(oa);
        assertThat(cancel(a, oa).getResponse().getStatus()).isEqualTo(200);
        assertThat(usage("REVIVE")).isZero();

        Customer x = customer(acme, "rx@acme.test");
        cart(x, shared, 1);
        CountDownLatch inPaystack = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        slowPaystackFor(x.email(), inPaystack, release);
        ExecutorService ex = Executors.newFixedThreadPool(3);
        try {
            Future<MvcResult> stuck = ex.submit(() -> checkout(x, null, null));
            assertThat(inPaystack.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(webhook(g, event("charge.success", ref, totalMinor(oa)))).isEqualTo(200);
            awaitBlocked(1, 5_000);
            assertThat(blockedLocks()).as("the revival waits for the slow checkout's stock").isPositive();

            couponIsNotHeld(ex, "REVIVE", cid, "y2");

            release.countDown();
            assertThat(stuck.get(30, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        } finally {
            release.countDown();
            ex.shutdownNow();
        }
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(orderStatus(acme, oa)).isEqualTo("COMPLETED"));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(usage("REVIVE")).as("y2 plus the revived order").isEqualTo(2));
        assertThat(jdbc.queryForObject("SELECT coupon_redemption_released FROM tenant_acme.orders WHERE order_number = ?",
                Boolean.class, oa)).isFalse();
    }

    // ---- P2: owner cancel and charge.failed of two orders sharing a coupon and a product do not deadlock

    @Test
    void cancelAndFailedWebhookOfTwoOrdersSharingCouponAndProductDoNotDeadlock() throws Exception {
        Gateway g = configureGateway(acme);
        createCoupon(body("DLK", 10));
        long p = product(acme, "SharedD", "100.00", 50);
        Customer a = customer(acme, "da@acme.test");
        cart(a, p, 1);
        String oa = place(a, "DLK");
        Customer b = customer(acme, "db@acme.test");
        cart(b, p, 1);
        String ob = place(b, "DLK");
        assertThat(usage("DLK")).isEqualTo(2);

        ExecutorService ex = Executors.newFixedThreadPool(2);
        MvcResult cancelled;
        try (Connection conn = jdbc.getDataSource().getConnection()) {
            conn.setAutoCommit(false);
            try (var st = conn.createStatement()) {
                // hold the coupon row so both transactions line up behind it with their other locks taken
                st.execute("SELECT id FROM tenant_acme.coupons WHERE code = 'DLK' FOR NO KEY UPDATE");
                webhook(g, event("charge.failed", reference(ob), totalMinor(ob)));
                awaitBlocked(1, 3_000);
                Future<MvcResult> c = ex.submit(() -> cancel(a, oa));
                awaitBlocked(2, 3_000);
                conn.rollback();
                cancelled = c.get(30, TimeUnit.SECONDS);
            }
        } finally {
            ex.shutdownNow();
        }
        assertThat(cancelled.getResponse().getStatus()).as(cancelled.getResponse().getContentAsString()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(orderStatus(acme, ob)).isEqualTo("FAILED"));
        assertThat(orderStatus(acme, oa)).isEqualTo("CANCELLED");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(usage("DLK")).isZero());
    }

    // ---- a deadlock that does happen is retried in-process (whole transaction), never a 500

    private long deadlocks() {
        return jdbc.queryForObject("SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()", Long.class);
    }

    @Test
    void aRealDeadlockVictimIsRetried_cleanly_neverA500() throws Exception {
        createCoupon(body("VICTIM", 10));
        long p = product(acme, "VictimP", "100.00", 50);
        Customer a = customer(acme, "va@acme.test");
        cart(a, p, 1);
        String oa = place(a, "VICTIM");
        assertThat(usage("VICTIM")).isEqualTo(1);
        long deadlocksBefore = deadlocks();

        ExecutorService ex = Executors.newFixedThreadPool(2);
        MvcResult r;
        try (Connection conn = jdbc.getDataSource().getConnection()) {
            conn.setAutoCommit(false);
            try (var st = conn.createStatement()) {
                st.execute("SELECT id FROM tenant_acme.coupons WHERE code = 'VICTIM' FOR NO KEY UPDATE");
                // the cancel writes its stock rows, then waits for the coupon row (last)
                Future<MvcResult> c = ex.submit(() -> cancel(a, oa));
                awaitBlocked(1, 5_000);
                // close the cycle at once: this session now wants the stock rows the cancel holds. Postgres aborts
                // the cancel (it has waited longest, its deadlock check runs first); the lock is then ours.
                ex.submit(() -> st.execute("SELECT id FROM tenant_acme.inventory_layers WHERE product_id = " + p
                        + " FOR UPDATE")).get(30, TimeUnit.SECONDS);
                // the retried cancel now waits for our stock lock; it cannot finish before we let go
                Thread.sleep(300);
                assertThat(c.isDone()).as("retried, waiting, not failed").isFalse();
                conn.rollback();
                r = c.get(30, TimeUnit.SECONDS);
            }
        } finally {
            ex.shutdownNow();
        }
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(orderStatus(acme, oa)).isEqualTo("CANCELLED");
        assertThat(usage("VICTIM")).as("released exactly once despite the aborted first attempt").isZero();
        assertThat(reservedQty(acme, p)).isZero();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(deadlocks()).as("Postgres really detected a deadlock").isGreaterThan(deadlocksBefore));
    }

    // ---- X17: delete locks the coupon's orders BEFORE the coupon

    @Test
    void deleteWaitingForAnOrderRowDoesNotHoldTheCoupon() throws Exception {
        long cid = createCoupon(body("DELWAIT", 10));
        Customer a = customer(acme, "dw@acme.test");
        cart(a, product(acme, "DelP", "100.00", 50), 1);
        String oa = place(a, "DELWAIT");
        assertThat(cancel(a, oa).getResponse().getStatus()).isEqualTo(200);
        assertThat(usage("DELWAIT")).isZero();

        ExecutorService ex = Executors.newFixedThreadPool(3);
        MvcResult deleted;
        try (Connection conn = jdbc.getDataSource().getConnection()) {
            conn.setAutoCommit(false);
            try (var st = conn.createStatement()) {
                st.execute("SELECT id FROM tenant_acme.orders WHERE order_number = '" + oa + "' FOR UPDATE");
                Future<MvcResult> d = ex.submit(() -> send(delete("/api/v1/coupon/" + cid), null));
                awaitBlocked(1, 5_000);
                assertThat(blockedLocks()).as("the delete waits for the order row").isPositive();

                Customer y = customer(acme, "dy@acme.test");
                cart(y, product(acme, "DelY", "100.00", 50), 1);
                long t0 = System.nanoTime();
                MvcResult r = ex.submit(() -> checkout(y, null, "couponCode=DELWAIT")).get(30, TimeUnit.SECONDS);
                long elapsed = (System.nanoTime() - t0) / 1_000_000;
                assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
                assertThat(elapsed).as("the delete holds no coupon lock while it waits for orders").isLessThan(3_000);
                conn.rollback();
                deleted = d.get(30, TimeUnit.SECONDS);
            }
        } finally {
            ex.shutdownNow();
        }
        // either the order wait timed out (503) or it then saw y's live redemption (409); never deleted, never a 500
        assertThat(deleted.getResponse().getStatus()).as(deleted.getResponse().getContentAsString()).isIn(409, 503);
        assertThat(usage("DELWAIT")).isEqualTo(1);
    }
}
