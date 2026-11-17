package com.example.perfume_budget.checkout;

import com.example.perfume_budget.service.DbLockTimeout;
import com.example.perfume_budget.support.TestTenants;
import com.example.perfume_budget.task.ReservedProductCleanupTask;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantIdentity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * API-04 rework 2: regression tests for the re-review probes P4 / P5, the pre-check that decides refusals and
 * replays before a redemption is reserved, and the test gaps found by the reviewer's surviving mutants
 * (X01, X04, X05, X11b, X12b, X18, X19; X07 is in CouponRedemptionIntegrationTest's V12 test).
 */
class CouponRedemptionRegressionIntegrationTest extends AbstractCheckoutIntegrationTest {

    @Autowired ReservedProductCleanupTask cleanupTask;
    @Autowired DbLockTimeout lockTimeout;
    @Autowired PlatformTransactionManager txManager;
    @PersistenceContext EntityManager em;

    private Cookie adminA;

    @BeforeEach
    void admin() throws Exception {
        adminA = cookie("accessToken", setCookieValue(loginPassword("acme", acme.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken"));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
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

    private int liveOrders(long couponId) {
        return jdbc.queryForObject("SELECT count(*) FROM tenant_acme.orders WHERE coupon_id = ? AND NOT coupon_redemption_released "
                + "AND status NOT IN ('CANCELLED','FAILED')", Integer.class, couponId);
    }

    private String place(Customer c, String code) throws Exception {
        MvcResult r = checkout(c, null, "couponCode=" + code);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r).at("/data/orderNumber").asText();
    }

    private record Timed(MvcResult result, long millis) {}

    /** Runs the request while another session holds the coupon row lock; the lock is released afterwards. */
    private Timed whileCouponLocked(String code, java.util.concurrent.Callable<MvcResult> request) throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try (Connection conn = jdbc.getDataSource().getConnection()) {
            conn.setAutoCommit(false);
            try (var st = conn.createStatement()) {
                st.execute("SELECT id FROM tenant_acme.coupons WHERE code = '" + code + "' FOR NO KEY UPDATE");
                long t0 = System.nanoTime();
                MvcResult r = ex.submit(request).get(30, TimeUnit.SECONDS);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                conn.rollback();
                return new Timed(r, ms);
            }
        } finally {
            ex.shutdownNow();
        }
    }

    // ---- P4: same product, same coupon, concurrent: the counter always equals the live orders

    @Test
    void sameProductConcurrentCouponCheckoutsNeverLeakTheCounter() throws Exception {
        long cid = createCoupon(body("SAMEP", 3, null));
        long p = product(acme, "Hot", "100.00", 100);
        List<Customer> cs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Customer c = customer(acme, "sp" + i + "@acme.test");
            cart(c, p, 1);
            cs.add(c);
        }
        ExecutorService ex = Executors.newFixedThreadPool(8);
        List<Integer> statuses = new ArrayList<>();
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> fs = new ArrayList<>();
            for (Customer c : cs) {
                fs.add(ex.submit(() -> {
                    go.await();
                    return checkout(c, null, "couponCode=SAMEP").getResponse().getStatus();
                }));
            }
            go.countDown();
            for (Future<Integer> f : fs) statuses.add(f.get(60, TimeUnit.SECONDS));
        } finally {
            ex.shutdownNow();
        }
        // API-05: same-product checkouts no longer fail on the product row, so the limit is reached exactly
        assertThat(statuses).as("statuses " + statuses).containsOnly(200, 400);
        assertThat(usage("SAMEP")).as("statuses " + statuses).isEqualTo(liveOrders(cid));
        assertThat(liveOrders(cid)).isEqualTo(3);
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(3);
        assertThat(active("SAMEP")).isFalse();
    }

    // ---- LOW 4: refusals and replays that do not need the counter never reserve (no transient over-count)

    @Test
    void perUserRefusalIsDecidedWithoutTouchingTheCoupon() throws Exception {
        createCoupon(body("PRE1", 10, 1));
        Customer c = ready("pre1@acme.test");
        String placed = place(c, "PRE1");
        // PAY-03: an open order would answer 409 OPEN_ORDER_EXISTS first; a paid one still holds the redemption
        jdbc.update("UPDATE tenant_acme.orders SET status = 'COMPLETED' WHERE order_number = ?", placed);
        cart(c, product(acme, "Second", "100.00", 50), 1); // a different cart: no fingerprint replay
        Timed t = whileCouponLocked("PRE1", () -> checkout(c, null, "couponCode=pre1"));
        assertThat(t.result().getResponse().getStatus()).as(t.result().getResponse().getContentAsString()).isEqualTo(400);
        assertThat(t.result().getResponse().getContentAsString()).contains("maximum number of times");
        assertThat(t.millis()).as("never waited for the coupon row").isLessThan(3_000);
        assertThat(usage("PRE1")).isEqualTo(1);
    }

    @Test
    void minimumAmountRefusalIsDecidedWithoutTouchingTheCoupon() throws Exception {
        Map<String, Object> b = body("PREMIN", 10, null);
        b.put("minimumCartAmountForDiscount", "500");
        createCoupon(b);
        Customer c = ready("premin@acme.test");
        Timed t = whileCouponLocked("PREMIN", () -> checkout(c, null, "couponCode=PREMIN"));
        assertThat(t.result().getResponse().getStatus()).as(t.result().getResponse().getContentAsString()).isEqualTo(400);
        assertThat(t.result().getResponse().getContentAsString()).contains("at least");
        assertThat(t.millis()).isLessThan(3_000);
        assertThat(usage("PREMIN")).isZero();
    }

    @Test
    void keyedAndFingerprintReplaysAreAnsweredWithoutTouchingTheCoupon() throws Exception {
        createCoupon(body("PREREP", 10, null));
        Customer keyed = ready("prekey@acme.test");
        MvcResult first = checkout(keyed, "key-prerep-0001", "couponCode=PREREP");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        Customer plain = ready("prefp@acme.test");
        place(plain, "PREREP");
        assertThat(usage("PREREP")).isEqualTo(2);

        Timed k = whileCouponLocked("PREREP", () -> checkout(keyed, "key-prerep-0001", "couponCode=PREREP"));
        assertThat(k.result().getResponse().getStatus()).as(k.result().getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(k.result()).at("/data/idempotentReplay").asBoolean()).isTrue();
        assertThat(k.millis()).isLessThan(3_000);

        Timed f = whileCouponLocked("PREREP", () -> checkout(plain, null, "couponCode=prerep"));
        assertThat(f.result().getResponse().getStatus()).as(f.result().getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(f.result()).at("/data/idempotentReplay").asBoolean()).isTrue();
        assertThat(f.millis()).isLessThan(3_000);
        assertThat(usage("PREREP")).isEqualTo(2);
    }

    // ---- X11b / X12b: the reservation UPDATE is the only date guard at checkout

    @Test
    void aCouponThatHasNotStartedIsRefusedAtCheckout_andNothingIsCounted() throws Exception {
        Map<String, Object> b = body("LATER", 10, null);
        b.put("startDate", LocalDate.now().plusDays(2).toString());
        createCoupon(b);
        MvcResult r = checkout(ready("later@acme.test"), null, "couponCode=LATER");
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).contains("not yet active");
        assertThat(usage("LATER")).isZero();
        assertThat(count(acme, "orders")).isZero();
    }

    @Test
    void anExpiredCouponIsRefusedAtCheckout_andNothingIsCounted() throws Exception {
        createCoupon(body("OLDONE", 10, null));
        jdbc.update("UPDATE tenant_acme.coupons SET start_date = current_date - 10, expiration_date = current_date - 1 "
                + "WHERE code = 'OLDONE'");
        MvcResult r = checkout(ready("old@acme.test"), null, "couponCode=OLDONE");
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).contains("expired");
        assertThat(usage("OLDONE")).isZero();
        assertThat(count(acme, "orders")).isZero();
    }

    // ---- X01: a release never takes the counter below zero

    @Test
    void releaseNeverTakesTheCounterBelowZero() throws Exception {
        createCoupon(body("FLOOR", 10, null));
        Customer c = ready("floor@acme.test");
        String order = place(c, "FLOOR");
        jdbc.update("UPDATE tenant_acme.coupons SET usage_count = 0 WHERE code = 'FLOOR'"); // e.g. fixed by hand
        MvcResult r = cancel(c, order);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(usage("FLOOR")).isZero();
    }

    // ---- X18: an admin PUT of isActive clears limit_deactivated (a release never overrides the admin)

    @Test
    void anAdminPutKeepingTheCouponOffIsNotUndoneByARelease() throws Exception {
        long id = createCoupon(body("PUTOFF", 1, null));
        Customer c = ready("putoff@acme.test");
        String order = place(c, "PUTOFF");
        assertThat(active("PUTOFF")).isFalse();
        assertThat(jdbc.queryForObject("SELECT limit_deactivated FROM tenant_acme.coupons WHERE id = ?", Boolean.class, id)).isTrue();

        Map<String, Object> edit = body("PUTOFF", 1, null);
        edit.put("isActive", false);
        edit.put("discountValue", "7");
        MvcResult put = send(put("/api/v1/coupon/" + id), edit);
        assertThat(put.getResponse().getStatus()).as(put.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT limit_deactivated FROM tenant_acme.coupons WHERE id = ?", Boolean.class, id)).isFalse();

        assertThat(cancel(c, order).getResponse().getStatus()).isEqualTo(200);
        assertThat(usage("PUTOFF")).isZero();
        assertThat(active("PUTOFF")).as("the admin switched it off: stays off").isFalse();
    }

    // ---- X19: the expiry job releases the coupon of an order closed without releasing it (straggler)

    @Test
    void expiryReleasesTheCouponOfAStraggler() throws Exception {
        createCoupon(body("STRAG", 10, null));
        String order = place(ready("strag@acme.test"), "STRAG");
        // closed outside the normal paths: CANCELLED, stock still reserved, redemption not released
        jdbc.update("UPDATE tenant_acme.orders SET status = 'CANCELLED' WHERE order_number = ?", order);
        jdbc.update("UPDATE tenant_acme.inventory_allocations SET created_at = now() - interval '5 hours'");
        assertThat(usage("STRAG")).isEqualTo(1);

        cleanupTask.cleanupExpiredReservations();

        assertThat(usage("STRAG")).isZero();
        assertThat(jdbc.queryForObject("SELECT coupon_redemption_released FROM tenant_acme.orders WHERE order_number = ?",
                Boolean.class, order)).isTrue();
        cleanupTask.cleanupExpiredReservations();
        assertThat(usage("STRAG")).as("exactly once").isZero();
    }

    // ---- X04 / X05: lock_timeout is transaction-local and the caller's value is restored afterwards

    private String setting() {
        return (String) em.createNativeQuery("SELECT setting FROM pg_settings WHERE name = 'lock_timeout'").getSingleResult();
    }

    private Number pid() {
        return (Number) em.createNativeQuery("SELECT pg_backend_pid()").getSingleResult();
    }

    @Test
    void lockTimeoutIsTransactionLocal_andRestoredForTheRestOfTheCallersTransaction() {
        TenantContext.set(TenantIdentity.tenant(acme.slug(), acme.schemaName()));
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Map<String, Object> seen = new LinkedHashMap<>();
        tx.executeWithoutResult(s -> {
            seen.put("pid", pid().intValue());
            seen.put("before", setting());
            em.createNativeQuery("SELECT set_config('lock_timeout', '1234ms', true)").getSingleResult();
            Supplier<String> inside = this::setting;
            seen.put("inside", lockTimeout.run("X", "x", inside));
            seen.put("after", setting());
        });
        assertThat(seen.get("inside")).as("bounded while the action runs").isEqualTo(String.valueOf(lockTimeout.timeoutMillis()));
        assertThat(seen.get("after")).as("the caller's own value is back (X05)").isEqualTo("1234");

        // same pooled connection after the commit: nothing leaked into the session (X04)
        String later = null;
        for (int i = 0; i < 20 && later == null; i++) {
            later = tx.execute(s -> pid().intValue() == (int) seen.get("pid") ? setting() : null);
        }
        assertThat(later).as("got the same connection back").isNotNull();
        assertThat(later).isEqualTo(seen.get("before"));
    }

    // ---- P5: a percent-encoded webhook path is never stored as a site visit

    @Test
    void encodedWebhookPathIsNotRecordedAsAVisit() throws Exception {
        Gateway g = configureGateway(acme);
        String ev = event("charge.failed", "Tnonexistent", 100);
        for (String prefix : new String[]{"/api/v1/%77ebhook/", "/api/v1/%57EBHOOK/", "/api/v1/web%68ook/"}) {
            mvc.perform(post(URI.create(prefix + g.token())).contentType("application/json")
                    .header("X-Paystack-Signature", hmac512(ev, g.secret())).content(ev)).andReturn();
        }
        // a normal page is still recorded (the filter is on)
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/product/visit-probe-" + System.nanoTime())
                .header("X-Tenant-ID", acme.slug())).andReturn();
        int hits = 0;
        int pages = 0;
        for (String s : jdbc.queryForList("SELECT table_schema FROM information_schema.tables WHERE table_name = 'site_visits'", String.class)) {
            hits += jdbc.queryForObject("SELECT count(*) FROM " + s + ".site_visits WHERE page LIKE ?", Integer.class, "%" + g.token() + "%");
            pages += jdbc.queryForObject("SELECT count(*) FROM " + s + ".site_visits", Integer.class);
        }
        assertThat(hits).isZero();
        assertThat(pages).as("visits are still recorded").isPositive();
    }
}
