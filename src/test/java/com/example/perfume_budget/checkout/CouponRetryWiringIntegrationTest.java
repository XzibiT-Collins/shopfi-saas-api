package com.example.perfume_budget.checkout;

import com.example.perfume_budget.dto.payment.response.PaystackVerifyTransactionResponse;
import com.example.perfume_budget.service.DbLockTimeout;
import com.example.perfume_budget.support.TestTenants;
import com.example.perfume_budget.task.ReservedProductCleanupTask;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * API-04 rework 3, test gaps of re-review #3 (surviving mutants R24, R25, R26, R05, R18).
 *
 * <p>Retry wiring (R24-R26): a Postgres trigger on {@code tenant_acme.orders} raises a REAL deadlock error
 * (SQLSTATE 40P01, through JDBC / Hibernate / Spring exactly like a deadlock victim) on the first UPDATE of one order;
 * the counter is a sequence, so it survives the rollback of the failed attempt. With the in-process retry the
 * operation succeeds on attempt 2 and its effect happens once; without it the caller sees the failure.
 */
class CouponRetryWiringIntegrationTest extends AbstractCheckoutIntegrationTest {

    @Autowired private ReservedProductCleanupTask cleanupTask;
    @Autowired private DbLockTimeout dbLockTimeout;

    private Cookie adminA;

    @BeforeEach
    void admin() throws Exception {
        adminA = cookie("accessToken", setCookieValue(loginPassword("acme", acme.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken"));
    }

    @AfterEach
    void dropFault() {
        jdbc.execute("DROP TRIGGER IF EXISTS it_retry_fault ON tenant_acme.orders");
        jdbc.execute("DROP FUNCTION IF EXISTS tenant_acme.it_retry_fault()");
        jdbc.execute("DROP SEQUENCE IF EXISTS tenant_acme.it_retry_fault_seq");
        jdbc.execute("DROP SEQUENCE IF EXISTS tenant_acme.it_retry_raised_seq");
    }

    // ---- helpers

    /** The first {@code failures} UPDATEs of order {@code orderId} fail with a real 40P01 (deadlock) error. */
    private void injectDeadlocks(long orderId, int failures) {
        dropFault();
        jdbc.execute("CREATE SEQUENCE tenant_acme.it_retry_fault_seq");
        jdbc.execute("CREATE SEQUENCE tenant_acme.it_retry_raised_seq");
        jdbc.execute("CREATE FUNCTION tenant_acme.it_retry_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.id = " + orderId + " AND nextval('tenant_acme.it_retry_fault_seq') <= " + failures + " THEN "
                + "PERFORM nextval('tenant_acme.it_retry_raised_seq'); "
                + "RAISE EXCEPTION 'injected deadlock' USING ERRCODE = '40P01'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER it_retry_fault BEFORE UPDATE ON tenant_acme.orders FOR EACH ROW "
                + "EXECUTE FUNCTION tenant_acme.it_retry_fault()");
    }

    /** How many times the fault trigger ran for the order: failed attempts + the UPDATEs of the attempt that got through. */
    private long faultCalls() {
        return jdbc.queryForObject("SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM tenant_acme.it_retry_fault_seq", Long.class);
    }

    /**
     * EVT-01 (d): how many injected deadlocks the trigger actually RAISED (sequences survive the rollback). Unlike
     * {@link #faultCalls()} (> 1 also when the operation simply updates the row twice) this is 0 without injection.
     */
    private long raised() {
        return jdbc.queryForObject("SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM tenant_acme.it_retry_raised_seq", Long.class);
    }

    /** One deadlock really hit attempt 1, and another attempt ran after it (the retry). */
    private void assertOneDeadlockThenARetry() {
        assertThat(raised()).as("the injected deadlock was raised exactly once").isEqualTo(1);
        assertThat(faultCalls()).as("a further attempt ran after the failed one").isGreaterThanOrEqualTo(1 + 1);
    }

    private static Map<String, Object> couponBody(String code, int usageLimit, String minimum, Integer perUser) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("couponCode", code);
        m.put("discountType", "FLAT");
        m.put("discountValue", "5");
        m.put("minimumCartAmountForDiscount", minimum);
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

    private int usage(String code) {
        return jdbc.queryForObject("SELECT usage_count FROM tenant_acme.coupons WHERE code = ?", Integer.class, code);
    }

    private long orderId(String orderNumber) {
        return jdbc.queryForObject("SELECT id FROM tenant_acme.orders WHERE order_number = ?", Long.class, orderNumber);
    }

    private String reference(String orderNumber) {
        return jdbc.queryForObject("SELECT p.provider_payment_reference FROM tenant_acme.payments p JOIN tenant_acme.orders o "
                + "ON o.id = p.order_id WHERE o.order_number = ?", String.class, orderNumber);
    }

    private long totalMinor(String orderNumber) {
        return jdbc.queryForObject("SELECT total_amount * 100 FROM tenant_acme.orders WHERE order_number = ?",
                BigDecimal.class, orderNumber).longValue();
    }

    private boolean released(String orderNumber) {
        return jdbc.queryForObject("SELECT coupon_redemption_released FROM tenant_acme.orders WHERE order_number = ?",
                Boolean.class, orderNumber);
    }

    private String place(Customer c, String key, String coupon) throws Exception {
        MvcResult r = checkout(c, key, coupon == null ? null : "couponCode=" + coupon);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r).at("/data/orderNumber").asText();
    }

    private String couponOrder(String code, String email) throws Exception {
        createCoupon(couponBody(code, 10, "0", null));
        Customer c = customer(acme, email);
        cart(c, product(acme, "P-" + code, "100.00", 20), 1);
        String o = place(c, null, code);
        assertThat(usage(code)).isEqualTo(1);
        owners.put(o, c);
        return o;
    }

    // ---- R24: admin order status runs in the retry wrapper

    @Test
    void adminCancelHitByADeadlockIsRetriedAndReleasesTheCouponOnce() throws Exception {
        String o = couponOrder("RW24", "rw24@acme.test");
        injectDeadlocks(orderId(o), 1);
        MvcResult r = send(put("/api/v1/order/update-order-status/" + o), Map.of("orderStatus", "CANCELLED"));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertOneDeadlockThenARetry();
        assertThat(orderStatus(acme, o)).isEqualTo("CANCELLED");
        assertThat(usage("RW24")).isZero();
        assertThat(released(o)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.order_audit_events WHERE order_id = ?",
                Integer.class, orderId(o))).as("one audit row: the failed attempt left nothing").isEqualTo(1);
    }

    @Test
    void adminCancelStillDeadlockedAfterAllAttemptsIs503TryAgainAndChangesNothing() throws Exception {
        String o = couponOrder("RW24B", "rw24b@acme.test");
        injectDeadlocks(orderId(o), 3);
        MvcResult r = send(put("/api/v1/order/update-order-status/" + o), Map.of("orderStatus", "CANCELLED"));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(503);
        assertThat(body(r).at("/data/code").asText()).isEqualTo("TRY_AGAIN");
        assertThat(r.getResponse().getHeader("Retry-After")).isEqualTo("1");
        assertThat(faultCalls()).isEqualTo(3);
        assertThat(raised()).as("every one of the 3 attempts was a deadlock victim").isEqualTo(3);
        assertThat(orderStatus(acme, o)).isNotEqualTo("CANCELLED");
        assertThat(usage("RW24B")).isEqualTo(1);
    }

    // ---- R25: the verify poll's failure settlement runs in the retry wrapper

    @Test
    void verifyPollFailureHitByADeadlockIsRetried() throws Exception {
        createCoupon(couponBody("RW25", 10, "0", null));
        Customer c = customer(acme, "rw25@acme.test");
        cart(c, product(acme, "P-RW25", "100.00", 20), 1);
        String o = place(c, null, "RW25");
        String ref = reference(o);
        verifyAnswer = new PaystackVerifyTransactionResponse(true, "ok", new PaystackVerifyTransactionResponse.Data(
                "failed", ref, BigDecimal.valueOf(totalMinor(o)), "GHS", "card", "Declined"));
        injectDeadlocks(orderId(o), 1);
        MvcResult r = verify(c, ref);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(r).at("/data/paymentStatus").asText()).isEqualTo("FAILED");
        assertOneDeadlockThenARetry();
        assertThat(orderStatus(acme, o)).isEqualTo("FAILED");
        assertThat(usage("RW25")).isZero();
    }

    // ---- R26: the expiry job runs each order in the retry wrapper

    @Test
    void expiryHitByADeadlockIsRetriedAndExpiresTheOrder() throws Exception {
        String o = couponOrder("RW26", "rw26@acme.test");
        jdbc.update("UPDATE tenant_acme.inventory_allocations SET created_at = now() - interval '5 hours' WHERE reference_id = ?", o);
        injectDeadlocks(orderId(o), 1);
        cleanupTask.cleanupExpiredReservations();
        assertOneDeadlockThenARetry();
        assertThat(orderStatus(acme, o)).isEqualTo("CANCELLED");
        assertThat(usage("RW26")).isZero();
        assertThat(released(o)).isTrue();
    }

    // ---- R05: the deferred coupon UPDATE at commit is bounded by lock_timeout

    @Test
    void cancelWhoseDeferredCouponUpdateWaitsOnALockedCouponIs503CouponBusyWithinTheLimit() throws Exception {
        String o = couponOrder("RW05", "rw05@acme.test");
        Object target = AopTestUtils.getUltimateTargetObject(dbLockTimeout);
        long original = (long) ReflectionTestUtils.getField(target, "timeoutMillis");
        ReflectionTestUtils.setField(target, "timeoutMillis", 1_000L);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        MvcResult r;
        long elapsedMs;
        try (Connection conn = jdbc.getDataSource().getConnection()) {
            conn.setAutoCommit(false);
            try (var st = conn.createStatement()) {
                st.execute("SELECT id FROM tenant_acme.coupons WHERE code = 'RW05' FOR NO KEY UPDATE");
                long t0 = System.nanoTime();
                Future<MvcResult> f = ex.submit(() -> cancel(ownerOf(o), o));
                try {
                    r = f.get(6, TimeUnit.SECONDS); // 1 s limit; an unbounded wait would block until we roll back
                } catch (TimeoutException e) {
                    fail("cancel still waiting for the coupon row after 6 s: the deferred coupon UPDATE has no lock_timeout");
                    return;
                }
                elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            } finally {
                conn.rollback();
            }
        } finally {
            ReflectionTestUtils.setField(target, "timeoutMillis", original);
            ex.shutdownNow();
        }
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(503);
        assertThat(body(r).at("/data/code").asText()).isEqualTo("COUPON_BUSY");
        assertThat(r.getResponse().getHeader("Retry-After")).isEqualTo("1");
        assertThat(elapsedMs).isLessThan(5_000);
        // rolled back as a whole: nothing changed, and a retry once the row is free works
        assertThat(orderStatus(acme, o)).isNotEqualTo("CANCELLED");
        assertThat(released(o)).isFalse();
        assertThat(usage("RW05")).isEqualTo(1);
        assertThat(cancel(ownerOf(o), o).getResponse().getStatus()).isEqualTo(200);
        assertThat(usage("RW05")).isZero();
    }

    private final Map<String, Customer> owners = new java.util.concurrent.ConcurrentHashMap<>();

    private Customer ownerOf(String orderNumber) {
        return owners.get(orderNumber);
    }

    // ---- R18: the pre-check treats a coupon switched off only by its limit as redeemable (documented order)

    @Test
    void couponSwitchedOffAtItsLimitReportsMinimumAndPerUserRefusalsFirst() throws Exception {
        long id = createCoupon(couponBody("RW18", 1, "50", 1));
        Customer a = customer(acme, "rw18a@acme.test");
        cart(a, product(acme, "P-RW18", "100.00", 20), 1);
        place(a, null, "RW18");
        assertThat(usage("RW18")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT is_active FROM tenant_acme.coupons WHERE id = ?", Boolean.class, id)).isFalse();
        assertThat(jdbc.queryForObject("SELECT limit_deactivated FROM tenant_acme.coupons WHERE id = ?", Boolean.class, id)).isTrue();

        // below the minimum: the minimum-amount message, not "inactive" / "usage limit"
        Customer b = customer(acme, "rw18b@acme.test");
        cart(b, product(acme, "P-RW18-small", "10.00", 20), 1);
        MvcResult small = checkout(b, null, "couponCode=RW18");
        assertThat(small.getResponse().getStatus()).isEqualTo(400);
        assertThat(small.getResponse().getContentAsString()).contains("must be at least 50");

        // the customer who holds the only redemption, new request: the per-user message
        MvcResult again = checkout(a, "key-rw18-again-01", "couponCode=RW18");
        assertThat(again.getResponse().getStatus()).isEqualTo(400);
        assertThat(again.getResponse().getContentAsString()).contains("maximum number of times");
        assertThat(usage("RW18")).as("refusals never touch the counter").isEqualTo(1);

        // contrast: switched off by an admin, the pre-check is skipped and the reservation reports "inactive"
        assertThat(send(post("/api/v1/coupon/" + id + "/deactivate"), null).getResponse().getStatus()).isEqualTo(200);
        MvcResult off = checkout(b, null, "couponCode=RW18");
        assertThat(off.getResponse().getStatus()).isEqualTo(400);
        assertThat(off.getResponse().getContentAsString()).contains("Coupon is inactive");
        assertThat(usage("RW18")).isEqualTo(1);
    }
}
