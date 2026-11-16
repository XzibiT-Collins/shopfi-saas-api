package com.example.perfume_budget.checkout;

import com.example.perfume_budget.model.Payment;
import com.example.perfume_budget.notification.MailMessage;
import com.example.perfume_budget.service.DbLockTimeout;
import com.example.perfume_budget.support.TestTenants;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * API-04 rework 3 (re-review #3 probe Q1, made permanent): the payment-success side effects (customer receipt, admin
 * e-mail, admin in-app / WebSocket notification) run once per COMMITTED settlement. The settlement transaction is
 * re-run by the webhook / verify retry, and each attempt publishes the Payment event; only the attempt that commits
 * may reach SuccessfulOrderHandler, and an attempt that never commits sends nothing.
 *
 * <p>The coupon lock_timeout is lowered to 1 s for these tests (restored afterwards) so that "every attempt fails"
 * takes ~3 s instead of ~15 s.
 */
class PaymentEventAfterCommitIntegrationTest extends AbstractCheckoutIntegrationTest {

    private static final long TEST_LOCK_TIMEOUT_MS = 1_000;

    @Autowired private ConfigurableApplicationContext ctx;
    @Autowired private DbLockTimeout dbLockTimeout;

    private Cookie adminA;
    private Object lockTimeoutTarget;
    private long originalLockTimeout;

    @BeforeEach
    void setUp() throws Exception {
        adminA = cookie("accessToken", setCookieValue(loginPassword("acme", acme.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken"));
        lockTimeoutTarget = AopTestUtils.getUltimateTargetObject(dbLockTimeout);
        originalLockTimeout = (long) ReflectionTestUtils.getField(lockTimeoutTarget, "timeoutMillis");
        ReflectionTestUtils.setField(lockTimeoutTarget, "timeoutMillis", TEST_LOCK_TIMEOUT_MS);
    }

    @AfterEach
    void restoreLockTimeout() {
        ReflectionTestUtils.setField(lockTimeoutTarget, "timeoutMillis", originalLockTimeout);
    }

    // ---- helpers

    /** Every Payment event PUBLISHED (one per settlement attempt that got that far), committed or not. */
    private final class PublishedPaymentEvents implements ApplicationListener<ApplicationEvent>, AutoCloseable {
        final Map<String, Integer> byRef = new ConcurrentHashMap<>();

        PublishedPaymentEvents() {
            ctx.addApplicationListener(this);
        }

        @Override
        public void onApplicationEvent(ApplicationEvent e) {
            if (e instanceof PayloadApplicationEvent<?> p && p.getPayload() instanceof Payment pay) {
                byRef.merge(String.valueOf(pay.getProviderPaymentReference()), 1, Integer::sum);
            }
        }

        int of(String ref) {
            return byRef.getOrDefault(ref, 0);
        }

        @Override
        public void close() {
            ctx.getBean(ApplicationEventMulticaster.class).removeApplicationListener(this);
        }
    }

    private long createCoupon(String code) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("couponCode", code);
        m.put("discountType", "FLAT");
        m.put("discountValue", "5");
        m.put("minimumCartAmountForDiscount", "0");
        m.put("usageLimit", 10);
        m.put("isActive", true);
        m.put("startDate", LocalDate.now().minusDays(1).toString());
        m.put("expirationDate", LocalDate.now().plusDays(30).toString());
        MvcResult r = mvc.perform(post("/api/v1/coupon/add-coupon").header("X-Tenant-ID", acme.slug()).cookie(adminA)
                .contentType("application/json").content(json.writeValueAsString(m))).andReturn();
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

    private String paymentStatus(String orderNumber) {
        return jdbc.queryForObject("SELECT p.status FROM tenant_acme.payments p JOIN tenant_acme.orders o "
                + "ON o.id = p.order_id WHERE o.order_number = ?", String.class, orderNumber);
    }

    private long totalMinor(String orderNumber) {
        return jdbc.queryForObject("SELECT total_amount * 100 FROM tenant_acme.orders WHERE order_number = ?",
                BigDecimal.class, orderNumber).longValue();
    }

    private int blockedLocks() {
        return jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Integer.class);
    }

    private String place(Customer c, String coupon) throws Exception {
        MvcResult r = checkout(c, null, coupon == null ? null : "couponCode=" + coupon);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r).at("/data/orderNumber").asText();
    }

    /** E-mails really handed to the mail transport whose subject contains {@code subjectPart}. */
    private int mails(String subjectPart) {
        return (int) Mockito.mockingDetails(mailTransport).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("send"))
                .map(i -> (MailMessage) i.getArgument(0))
                .filter(m -> m.subject() != null && m.subject().contains(subjectPart))
                .count();
    }

    private int receipts(String orderNumber) {
        return mails("Your Order Receipt - " + orderNumber);
    }

    private int adminPaymentMails(String orderNumber) {
        return mails("Payment successful for order #" + orderNumber + ":");
    }

    private int adminNotifications(String orderNumber) {
        return jdbc.queryForObject("SELECT count(*) FROM tenant_acme.notifications WHERE type = 'PAYMENT_SUCCESS' "
                + "AND reference_id = ?", Integer.class, orderNumber);
    }

    private void assertSideEffects(String orderNumber, int expected) {
        assertThat(receipts(orderNumber)).as("customer receipts for " + orderNumber).isEqualTo(expected);
        assertThat(adminPaymentMails(orderNumber)).as("admin payment e-mails for " + orderNumber).isEqualTo(expected);
        assertThat(adminNotifications(orderNumber)).as("admin notifications for " + orderNumber).isEqualTo(expected);
    }

    /** Cancelled coupon order whose late charge.success revives it (the coupon UPDATE is deferred to commit). */
    private String cancelledCouponOrder(String code, String email) throws Exception {
        createCoupon(code);
        Customer a = customer(acme, email);
        cart(a, product(acme, "P-" + code, "100.00", 50), 1);
        String order = place(a, code);
        assertThat(cancel(a, order).getResponse().getStatus()).isEqualTo(200);
        assertThat(usage(code)).isZero();
        return order;
    }

    // ---- tests

    @Test
    void retriedRevivalWhoseFirstAttemptFailsAtCommitSendsReceiptExactlyOnce() throws Exception {
        String order = cancelledCouponOrder("AFTERC1", "ac1@acme.test");
        String ref = reference(order);
        Gateway g = configureGateway(acme);
        try (PublishedPaymentEvents published = new PublishedPaymentEvents();
             Connection conn = jdbc.getDataSource().getConnection()) {
            conn.setAutoCommit(false);
            try (var st = conn.createStatement()) {
                st.execute("SELECT id FROM tenant_acme.coupons WHERE code = 'AFTERC1' FOR NO KEY UPDATE");
                assertThat(webhook(g, event("charge.success", ref, totalMinor(order)))).isEqualTo(200);
                // attempt 1 published, then its deferred coupon UPDATE timed out at commit (COUPON_BUSY, rolled back);
                // attempt 2 published and is now waiting for the coupon row
                await().atMost(Duration.ofSeconds(15)).until(() -> published.of(ref) >= 2);
                assertThat(orderStatus(acme, order)).isEqualTo("CANCELLED");
                assertSideEffects(order, 0);
            } finally {
                conn.rollback();
            }
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(orderStatus(acme, order)).isEqualTo("COMPLETED"));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertSideEffects(order, 1));
            Thread.sleep(1_500); // nothing else is still on its way
            assertThat(published.of(ref)).as("settlement attempts that published (a retry happened)").isBetween(2, 3);
            assertSideEffects(order, 1);
            assertThat(usage("AFTERC1")).isEqualTo(1);
        }
    }

    @Test
    void settlementWhoseEveryAttemptFailsSendsNothingAndAPaystackRedeliverySendsOnce() throws Exception {
        String order = cancelledCouponOrder("AFTERC2", "ac2@acme.test");
        String ref = reference(order);
        Gateway g = configureGateway(acme);
        try (PublishedPaymentEvents published = new PublishedPaymentEvents();
             Connection conn = jdbc.getDataSource().getConnection()) {
            conn.setAutoCommit(false);
            try (var st = conn.createStatement()) {
                st.execute("SELECT id FROM tenant_acme.coupons WHERE code = 'AFTERC2' FOR NO KEY UPDATE");
                assertThat(webhook(g, event("charge.success", ref, totalMinor(order)))).isEqualTo(200);
                await().atMost(Duration.ofSeconds(20)).until(() -> published.of(ref) >= 3);
                await().atMost(Duration.ofSeconds(10)).until(() -> blockedLocks() >= 1);  // attempt 3 waits ...
                await().atMost(Duration.ofSeconds(10)).until(() -> blockedLocks() == 0);  // ... and gives up
                Thread.sleep(1_500); // let any (wrongly) queued handler run
                assertThat(published.of(ref)).as("all attempts published and none committed").isEqualTo(3);
                assertThat(orderStatus(acme, order)).isEqualTo("CANCELLED");
                assertThat(paymentStatus(order)).isNotEqualTo("COMPLETED");
                assertSideEffects(order, 0);
            } finally {
                conn.rollback();
            }
            assertSideEffects(order, 0);
            assertThat(usage("AFTERC2")).isZero();

            // Paystack redelivers the event: now it commits, and the customer gets exactly one receipt
            assertThat(webhook(g, event("charge.success", ref, totalMinor(order)))).isEqualTo(200);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(orderStatus(acme, order)).isEqualTo("COMPLETED"));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertSideEffects(order, 1));
            // a duplicate delivery of the same event changes nothing and sends nothing
            assertThat(webhook(g, event("charge.success", ref, totalMinor(order)))).isEqualTo(200);
            Thread.sleep(1_500);
            assertSideEffects(order, 1);
            assertThat(usage("AFTERC2")).isEqualTo(1);
        }
    }

    @Test
    void concurrentSuccessWebhooksOfTheSameProductSendOneReceiptPerPayment() throws Exception {
        Gateway g = configureGateway(acme);
        long p = product(acme, "AfterHot", "100.00", 100);
        List<String> orders = new ArrayList<>();
        Map<String, String> refs = new HashMap<>();
        for (int i = 0; i < 6; i++) {
            Customer c = customer(acme, "ach" + i + "@acme.test");
            cart(c, p, 1);
            String o = place(c, null);
            orders.add(o);
            refs.put(o, reference(o));
        }
        ExecutorService ex = Executors.newFixedThreadPool(orders.size());
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> fs = new ArrayList<>();
            for (String o : orders) {
                String ev = event("charge.success", refs.get(o), totalMinor(o));
                fs.add(ex.submit(() -> { go.await(); return webhook(g, ev); }));
            }
            go.countDown();
            for (Future<Integer> f : fs) assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            ex.shutdownNow();
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            for (String o : orders) assertThat(orderStatus(acme, o)).isEqualTo("COMPLETED");
        });
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            for (String o : orders) assertSideEffects(o, 1);
        });
        Thread.sleep(1_500);
        for (String o : orders) assertSideEffects(o, 1);
    }

    @Test
    void verifyPollSettlementSendsTheReceiptOnceWithTheCustomersName() throws Exception {
        Customer c = customer(acme, "acv@acme.test");
        cart(c, product(acme, "AfterV", "100.00", 10), 1);
        String o = place(c, null);
        String ref = reference(o);
        verifyAnswer = new com.example.perfume_budget.dto.payment.response.PaystackVerifyTransactionResponse(true, "ok",
                new com.example.perfume_budget.dto.payment.response.PaystackVerifyTransactionResponse.Data(
                        "success", ref, BigDecimal.valueOf(totalMinor(o)), "GHS", "card", "Successful"));
        assertThat(body(verify(c, ref)).at("/data/paymentStatus").asText()).isEqualTo("COMPLETED");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertSideEffects(o, 1));
        // the receipt is rendered after commit from the re-read order: customer (lazy) and items are there
        MailMessage receipt = Mockito.mockingDetails(mailTransport).getInvocations().stream()
                .map(i -> (MailMessage) i.getArgument(0))
                .filter(m -> m.subject().contains("Your Order Receipt - " + o))
                .findFirst().orElseThrow();
        assertThat(receipt.to()).isEqualTo(c.email());
        assertThat(receipt.html()).contains("AfterV");
    }
}
