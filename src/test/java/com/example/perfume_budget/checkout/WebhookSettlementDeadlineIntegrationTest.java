package com.example.perfume_budget.checkout;

import com.example.perfume_budget.service.DbLockTimeout;
import com.example.perfume_budget.service.SettlementDeadline;
import com.example.perfume_budget.service.WebhookLockTimeout;
import com.example.perfume_budget.support.PostgresTestDatabase;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EVT-01d (2): every lock wait of a webhook settlement is limited to the settlement's REMAINING budget, not only the
 * attempt's first one. Before, the attempt's lock_timeout was capped to the budget left when the attempt started, and
 * each later wait (cart, products, ledger accounts) could take that whole limit again, and the coupon sections always
 * took their fixed 5 s, so one delivery could outlast settlement-deadline-ms (and Paystack's 30 s).
 *
 * <p>Timeline (budget 3 s, per-wait cap 8 s): the order row is held until ~1.8 s, then a second row the settlement
 * needs (cart / products / ledger) until ~4.5 s. Before: the second wait was allowed the attempt's ~3 s, so it got the
 * row at ~4.5 s and answered 200 a good 1.5 s past the budget. Now it may wait only the ~1.2 s left: 500 (redelivered)
 * at ~3 s, nothing committed. Every held row is released by the test with a bound; the request runs on its own thread
 * and is awaited with a timeout.
 */
class WebhookSettlementDeadlineIntegrationTest extends AbstractCheckoutIntegrationTest {

    private static final long BUDGET_MS = 3000;
    private static final long ORDER_RELEASED_AT_MS = 1800;
    private static final long SECOND_RELEASED_AT_MS = 4500;

    @Autowired WebhookLockTimeout webhookLockTimeout;
    @Autowired DbLockTimeout dbLockTimeout;
    @Autowired PlatformTransactionManager txm;
    @Autowired EntityManager entityManager;

    private long savedDeadline;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @BeforeEach
    void saveDeadline() {
        savedDeadline = webhookLockTimeout.deadlineMillis();
    }

    @AfterEach
    void restoreDeadline() throws InterruptedException {
        ReflectionTestUtils.setField(webhookLockTimeout, "deadlineMillis", savedDeadline);
        pool.shutdownNow();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    }

    private String placeOrder(Customer c) throws Exception {
        MvcResult r = checkout(c, null, null);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r).at("/data/orderNumber").asText();
    }

    private long totalMinor(String on) {
        return jdbc.queryForObject("SELECT total_amount * 100 FROM tenant_acme.orders WHERE order_number = ?",
                BigDecimal.class, on).longValue();
    }

    private String reference(String on) {
        return jdbc.queryForObject("SELECT p.provider_payment_reference FROM tenant_acme.payments p JOIN tenant_acme.orders o "
                + "ON o.id = p.order_id WHERE o.order_number = ?", String.class, on);
    }

    /** A row lock held on a separate, non-pooled connection (released by rollback on close). */
    private static final class Holder implements AutoCloseable {
        final Connection conn;
        final int pid;

        Holder(String lockSql) throws SQLException {
            conn = PostgresTestDatabase.connect();
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET lock_timeout = '10s'"); // the holder itself never waits unbounded
                try (ResultSet rs = st.executeQuery(lockSql)) {
                    assertThat(rs.next()).as("held a row: " + lockSql).isTrue();
                }
                try (ResultSet rs = st.executeQuery("SELECT pg_backend_pid()")) {
                    rs.next();
                    pid = rs.getInt(1);
                }
            }
        }

        @Override
        public void close() throws SQLException {
            try {
                conn.rollback();
            } finally {
                conn.close();
            }
        }
    }

    private boolean someoneWaitsFor(Holder h) {
        return jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                Integer.class, h.pid) > 0;
    }

    private static void sleepUntil(long t0, long atMs) throws InterruptedException {
        long left = atMs - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        if (left > 0) {
            Thread.sleep(left);
        }
    }

    @ParameterizedTest(name = "second wait on {0}")
    @ValueSource(strings = {"cart", "products", "ledger"})
    void aSecondLockWaitInTheSameAttempt_isLimitedToTheBudgetLeft_soTheDeliveryEndsAtTheDeadline(String second)
            throws Exception {
        Gateway g = configureGateway(acme);
        long p = product(acme, "Dl" + second, "50.00", 10);
        Customer c = customer(acme, "dl-" + second + "@acme.test");
        cart(c, p, 1);
        String on = placeOrder(c);
        // the checkout keeps the cart; the payment settlement empties it (CAT-04 cart lock)
        assertThat(count(acme, "carts")).as("the customer's cart is still there").isPositive();
        String body = event("charge.success", reference(on), totalMinor(on));
        awaitBackgroundIdle();
        String secondSql = switch (second) {
            case "cart" -> "SELECT id FROM tenant_acme.carts WHERE user_id = " + c.id() + " FOR UPDATE";
            case "products" -> "SELECT id FROM tenant_acme.products WHERE id = " + p + " FOR NO KEY UPDATE";
            default -> "SELECT id FROM tenant_acme.ledger_accounts ORDER BY id FOR UPDATE";
        };
        ReflectionTestUtils.setField(webhookLockTimeout, "deadlineMillis", BUDGET_MS);
        assertThat(webhookLockTimeout.timeoutMillis()).as("per-wait cap well above the budget").isEqualTo(8000L);

        long[] answer;
        try (Holder orderRow = new Holder("SELECT id FROM tenant_acme.orders WHERE order_number = '" + on + "' FOR UPDATE");
             Holder secondRow = new Holder(secondSql)) {
            long t0 = System.nanoTime();
            Future<long[]> f = pool.submit(() -> {
                int status = webhook(g, body);
                return new long[]{status, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)};
            });
            long end = System.currentTimeMillis() + 10_000;
            while (!someoneWaitsFor(orderRow) && System.currentTimeMillis() < end) {
                Thread.sleep(10);
            }
            assertThat(someoneWaitsFor(orderRow)).as("the settlement waits for the order row").isTrue();
            sleepUntil(t0, ORDER_RELEASED_AT_MS);
            orderRow.conn.rollback();
            sleepUntil(t0, SECOND_RELEASED_AT_MS);
            secondRow.conn.rollback();
            answer = f.get(30, TimeUnit.SECONDS);
        }
        System.out.println("EVT01D deadline " + second + ": status=" + answer[0] + " after " + answer[1] + " ms");
        assertThat(answer[0]).as("not applied within the budget: answered 500, redelivered").isEqualTo(500);
        assertThat(answer[1]).as("ended at the deadline (%d ms), not when the second row was released", BUDGET_MS)
                .isBetween(BUDGET_MS - 300, BUDGET_MS + 700);
        assertThat(orderStatus(acme, on)).isNotEqualTo("COMPLETED");

        ReflectionTestUtils.setField(webhookLockTimeout, "deadlineMillis", savedDeadline);
        assertThat(webhook(g, body)).as("the redelivery applies it").isEqualTo(200);
        assertThat(orderStatus(acme, on)).isEqualTo("COMPLETED");
        assertPooledTimeoutsAreTheDefaults();
    }

    /** Postgres renders a duration setting as e.g. "700ms", "5s" or "0". */
    private static long millis(Object setting) {
        String s = setting.toString();
        if (s.endsWith("ms")) return Long.parseLong(s.substring(0, s.length() - 2));
        if (s.endsWith("min")) return Long.parseLong(s.substring(0, s.length() - 3)) * 60_000;
        if (s.endsWith("s")) return Long.parseLong(s.substring(0, s.length() - 1)) * 1000;
        return Long.parseLong(s);
    }

    private long setting(String name) {
        return millis(entityManager.createNativeQuery("SELECT current_setting('" + name + "')").getSingleResult());
    }

    @Test
    void aCouponSectionInsideASettlement_waitsAtMostTheBudgetLeft_andPutsBackTheDerivedLimit() {
        TransactionTemplate tx = new TransactionTemplate(txm);
        // outside a settlement: the coupon section's fixed 5 s, then the caller's previous value
        tx.executeWithoutResult(s -> {
            long inside = dbLockTimeout.run("X", "busy", () -> setting("lock_timeout"));
            assertThat(inside).isEqualTo(dbLockTimeout.timeoutMillis()).isEqualTo(5000L);
            assertThat(setting("lock_timeout")).isZero();
        });
        // inside a settlement with ~700 ms left: never more than that, and the restore is derived too
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(700);
        long[] seen = SettlementDeadline.run(deadline, 8000, () -> tx.execute(s -> {
            long inside = dbLockTimeout.run("X", "busy", () -> setting("lock_timeout"));
            return new long[]{inside, setting("lock_timeout"), setting("statement_timeout")};
        }));
        assertThat(seen[0]).as("coupon wait inside the budget").isBetween(1L, 700L);
        assertThat(seen[1]).as("restored to the derived limit, not a stale one").isBetween(1L, 700L);
        assertThat(seen[2]).as("statement_timeout derived from the budget").isBetween(1L, 700L);
        // with plenty of budget the coupon cap (5 s) still applies, and the restore is the settlement's 8 s cap
        long far = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        long[] wide = SettlementDeadline.run(far, 8000, () -> tx.execute(s -> {
            long inside = dbLockTimeout.run("X", "busy", () -> setting("lock_timeout"));
            return new long[]{inside, setting("lock_timeout")};
        }));
        assertThat(wide[0]).isEqualTo(5000L);
        assertThat(wide[1]).isEqualTo(8000L);
    }

    @Test
    void aCouponSectionAfterTheDeadline_failsAtOnce_insteadOfWaiting() {
        TransactionTemplate tx = new TransactionTemplate(txm);
        long passed = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(1);
        List<Throwable> thrown = new ArrayList<>();
        SettlementDeadline.run(passed, 8000, () -> {
            try {
                tx.executeWithoutResult(s -> dbLockTimeout.run("X", "busy", () -> 1));
            } catch (RuntimeException e) {
                thrown.add(e);
            }
            return null;
        });
        assertThat(thrown).singleElement().isInstanceOf(
                com.example.perfume_budget.service.WebhookProcessorImpl.WebhookDeadlineExceededException.class);
    }

    private void assertPooledTimeoutsAreTheDefaults() throws Exception {
        List<Connection> borrowed = new ArrayList<>();
        try {
            for (int i = 0; i < 10; i++) borrowed.add(jdbc.getDataSource().getConnection());
            for (Connection conn : borrowed) {
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT current_setting('lock_timeout'), current_setting('statement_timeout')")) {
                    rs.next();
                    assertThat(rs.getString(1)).as("pooled lock_timeout").isEqualTo("0");
                    assertThat(rs.getString(2)).as("pooled statement_timeout").isEqualTo("0");
                }
            }
        } finally {
            for (Connection conn : borrowed) conn.close();
        }
    }
}
