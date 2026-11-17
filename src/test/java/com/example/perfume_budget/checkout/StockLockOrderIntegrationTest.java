package com.example.perfume_budget.checkout;

import com.example.perfume_budget.dto.payment.request.PaystackInitiatePaymentRequest;
import com.example.perfume_budget.dto.payment.response.PaystackInitiateTransactionResponse;
import com.example.perfume_budget.dto.payment.response.PaystackVerifyTransactionResponse;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.support.TestTenants;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * API-05 rework: every path that changes stock takes the product rows through ProductStockLocks (one
 * {@code SELECT ... ORDER BY id FOR NO KEY UPDATE}) BEFORE it writes any product, layer, allocation or movement row.
 *
 * <p>Deterministic, no timing: a second JDBC connection holds one product row; the operation under test runs in another
 * thread until Postgres reports it blocked by that connection ({@code pg_blocking_pids}). At that moment it must be
 * waiting in the ProductStockLocks statement and must not yet hold a write lock on any stock table. A path without the
 * lock (or with a partial one) is instead caught waiting in an {@code UPDATE products} or after it already wrote a
 * layer. Then the holder lets go and the operation must finish normally.
 */
@RecordApplicationEvents
class StockLockOrderIntegrationTest extends AbstractCheckoutIntegrationTest {

    @Autowired
    private ApplicationEvents events;

    private Cookie admin;

    @BeforeEach
    void adminLogin() throws Exception {
        admin = cookie("accessToken", setCookieValue(loginPassword("acme", acme.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken"));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** A transaction on its own connection holding product rows FOR NO KEY UPDATE until it is closed. */
    private final class Holder implements AutoCloseable {
        final Connection conn;
        final int pid;

        Holder(long... productIds) throws SQLException {
            conn = jdbc.getDataSource().getConnection();
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                for (long id : productIds) {
                    st.execute("SELECT id FROM tenant_acme.products WHERE id = " + id + " FOR NO KEY UPDATE");
                }
                try (ResultSet rs = st.executeQuery("SELECT pg_backend_pid()")) {
                    rs.next();
                    pid = rs.getInt(1);
                }
            }
        }

        void execute(String sql) throws SQLException {
            try (Statement st = conn.createStatement()) {
                st.execute(sql);
            }
        }

        void commit() throws SQLException {
            conn.commit();
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

    private record Blocked(int pid, String query) {}

    /** Waits (up to 20 s) until some backend is blocked by the holder. */
    private Blocked awaitBlockedBy(Holder h) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT pid, query FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))", h.pid);
            if (!rows.isEmpty()) {
                return new Blocked(((Number) rows.getFirst().get("pid")).intValue(), String.valueOf(rows.getFirst().get("query")));
            }
            Thread.sleep(20);
        }
        return fail("nothing was blocked by the held product row within 20 s");
    }

    /** Stock tables the backend has already written in its open transaction (RowExclusiveLock on the relation). */
    private List<String> stockTablesWrittenBy(int pid) {
        return jdbc.queryForList("SELECT c.relname FROM pg_locks l JOIN pg_class c ON c.oid = l.relation "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE l.pid = ? AND l.mode = 'RowExclusiveLock' "
                + "AND n.nspname = 'tenant_acme' AND c.relname IN ('products', 'inventory_layers', 'inventory_allocations', "
                + "'inventory_movements')", String.class, pid);
    }

    /** The operation waits in the ProductStockLocks statement and has written no stock row yet. */
    private Blocked assertWaitsInTheStockLockBeforeAnyStockWrite(Holder h) throws InterruptedException {
        Blocked b = awaitBlockedBy(h);
        assertThat(b.query()).as("blocked statement").containsIgnoringCase("ORDER BY id FOR NO KEY UPDATE");
        assertThat(stockTablesWrittenBy(b.pid())).as("stock tables written before the product lock").isEmpty();
        return b;
    }

    /** Can another transaction lock this product right now (NOWAIT)? */
    private boolean lockableNow(long productId) throws SQLException {
        try (Connection probe = jdbc.getDataSource().getConnection(); Statement ps = probe.createStatement()) {
            probe.setAutoCommit(false);
            try {
                ps.execute("SELECT id FROM tenant_acme.products WHERE id = " + productId + " FOR NO KEY UPDATE NOWAIT");
                return true;
            } catch (SQLException e) {
                return false;
            } finally {
                probe.rollback();
            }
        }
    }

    private MvcResult asAdmin(MockHttpServletRequestBuilder b, Object content) throws Exception {
        b.header("X-Tenant-ID", acme.slug()).cookie(admin);
        if (content != null) b.contentType("application/json").content(json.writeValueAsString(content));
        return mvc.perform(b).andReturn();
    }

    private <T> T whileHeld(Holder h, Callable<T> op, ThrowingCheck check) throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Future<T> f = ex.submit(op);
            check.run();
            h.commit();
            return f.get(60, TimeUnit.SECONDS);
        } finally {
            ex.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface ThrowingCheck {
        void run() throws Exception;
    }

    private String placeOrder(Customer c) throws Exception {
        MvcResult r = checkout(c, null, null);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r).at("/data/orderNumber").asText();
    }

    private long totalMinor(String orderNumber) {
        return jdbc.queryForObject("SELECT total_amount * 100 FROM tenant_acme.orders WHERE order_number = ?",
                BigDecimal.class, orderNumber).longValue();
    }

    private String reference(String orderNumber) {
        return jdbc.queryForObject("SELECT p.provider_payment_reference FROM tenant_acme.payments p JOIN tenant_acme.orders o "
                + "ON o.id = p.order_id WHERE o.order_number = ?", String.class, orderNumber);
    }

    private void awaitStatus(String orderNumber, String status) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline && !status.equals(orderStatus(acme, orderNumber))) {
            Thread.sleep(50);
        }
        assertThat(orderStatus(acme, orderNumber)).isEqualTo(status);
    }

    private int productInt(long productId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM tenant_acme.products WHERE id = ?", Integer.class, productId);
    }

    private int legacyLayers(long productId) {
        return jdbc.queryForObject("SELECT count(*) FROM tenant_acme.inventory_layers WHERE product_id = ? "
                + "AND source_type = 'LEGACY_BALANCE'", Integer.class, productId);
    }

    private void pay(Customer c, String orderNumber) throws Exception {
        String ref = reference(orderNumber);
        verifyAnswer = new PaystackVerifyTransactionResponse(true, "ok", new PaystackVerifyTransactionResponse.Data(
                "success", ref, BigDecimal.valueOf(totalMinor(orderNumber)), "GHS", "card", "Successful"));
        verify(c, ref);
        verifyAnswer = null;
        assertThat(orderStatus(acme, orderNumber)).isEqualTo("COMPLETED");
    }

    /** Two products of one family: a bulk unit (factor 10) and its base unit. Returns {bulk, base}. */
    private long[] family(int bulkStock, int baseStock) {
        long bulk = product(acme, "Bulk", "100.00", bulkStock);
        long base = product(acme, "Base", "12.00", baseStock);
        long fam = jdbc.queryForObject("INSERT INTO tenant_acme.product_families (family_code, name, base_unit_id) "
                + "VALUES (?, 'Fam', ?) RETURNING id", Long.class, "FAM-" + UUID.randomUUID(), base);
        jdbc.update("UPDATE tenant_acme.products SET family_id = ?, is_base_unit = false, conversion_factor = 10, "
                + "cost_price_amount = 50, cost_price_currency = 'GHS' WHERE id = ?", fam, bulk);
        jdbc.update("UPDATE tenant_acme.products SET family_id = ?, is_base_unit = true, conversion_factor = 1, "
                + "cost_price_amount = 5, cost_price_currency = 'GHS' WHERE id = ?", fam, base);
        return new long[]{bulk, base};
    }

    // ------------------------------------------------------------------- payment success (reviewer MEDIUM, R2 / R2b)

    @Test
    void paymentSuccessForItemsHighThenLow_waitsForTheLowerIdInTheStockLock_holdingNoProductRow() throws Exception {
        Gateway g = configureGateway(acme);
        long low = product(acme, "WLow", "50.00", 10);
        long high = product(acme, "WHigh", "60.00", 10);
        Customer c = customer(acme, "w2@acme.test");
        cart(c, high, 1); // order items: high, low
        cart(c, low, 1);
        String on = placeOrder(c);

        Future<Integer> answer;
        try (Holder h = new Holder(low)) {
            answer = webhookInBackground(g, event("charge.success", reference(on), totalMinor(on)));
            assertWaitsInTheStockLockBeforeAnyStockWrite(h);
            // before the rework the webhook had already UPDATEd the higher product (sold count) and held it here
            assertThat(lockableNow(high)).as("higher id still free while the webhook waits for the lower one").isTrue();
            h.commit();
        }
        assertThat(answer.get(60, TimeUnit.SECONDS)).as("answered once the settlement committed").isEqualTo(200);
        awaitStatus(on, "COMPLETED");
        assertThat(productInt(low, "sold_count")).isEqualTo(1);
        assertThat(productInt(high, "sold_count")).isEqualTo(1);
        assertThat(reservedQty(acme, low) + reservedQty(acme, high)).isZero();
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.example.perfume_budget.service.WebhookLockTimeout webhookLockTimeout;

    /**
     * EVT-01c: the webhook settles before it answers, so a stock row held by another transaction must not hold the
     * request (and Paystack's delivery) forever: each attempt's lock wait is bounded, the attempts are retried, and the
     * webhook then answers 500 (not acknowledged) with nothing changed. The redelivery after the row is free applies it.
     * (Without the bound this test hangs in the webhook call, which is how the EVT-01c b1 batch hung.)
     */
    @Test
    void aWebhookWaitingForAHeldStockRowGivesUpWithin_theBound_unacknowledged_andTheRedeliveryApplies() throws Exception {
        Gateway g = configureGateway(acme);
        long p = product(acme, "WHeld", "50.00", 10);
        Customer c = customer(acme, "wh@acme.test");
        cart(c, p, 1);
        String on = placeOrder(c);
        String body = event("charge.success", reference(on), totalMinor(on));
        long saved = webhookLockTimeout.timeoutMillis();
        org.springframework.test.util.ReflectionTestUtils.setField(webhookLockTimeout, "timeoutMillis", 300L);
        try (Holder h = new Holder(p)) {
            long t0 = System.nanoTime();
            Future<Integer> answer = webhookInBackground(g, body);
            assertThat(answer.get(30, TimeUnit.SECONDS)).as("not acknowledged while the row is held").isEqualTo(500);
            assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - t0)).as("bounded, not a hang").isLessThan(20);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                    Integer.class, h.pid)).as("nothing left waiting for the held row").isZero();
            h.commit();
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(webhookLockTimeout, "timeoutMillis", saved);
        }
        assertThat(orderStatus(acme, on)).isNotEqualTo("COMPLETED");
        assertThat(productInt(p, "sold_count")).isZero();
        assertThat(webhook(g, body)).as("the redelivery").isEqualTo(200);
        assertThat(orderStatus(acme, on)).isEqualTo("COMPLETED");
        assertThat(productInt(p, "sold_count")).isEqualTo(1);
    }

    @Test
    void checkoutAndPaymentWebhookOfTheSameTwoProductsInOppositeOrders_doNotDeadlock() throws Exception {
        // reviewer probe R2b: before the rework the webhook (items high, low) took high via its sold-count UPDATE and
        // then waited for low, while the checkout (ascending: low, high) held low and waited for high -> Postgres
        // "deadlock detected", the checkout was the victim: 503 after Paystack had already been called.
        Gateway g = configureGateway(acme);
        long low = product(acme, "DLow", "50.00", 10);
        long high = product(acme, "DHigh", "60.00", 10);
        Customer c = customer(acme, "d1@acme.test");
        cart(c, high, 1);
        cart(c, low, 1);
        String on = placeOrder(c);
        Customer x = customer(acme, "d2@acme.test");
        cart(x, low, 1);
        cart(x, high, 1);

        MvcResult xr;
        ExecutorService ex = Executors.newSingleThreadExecutor();
        Future<Integer> answer;
        try (Holder h = new Holder(high)) {
            answer = webhookInBackground(g, event("charge.success", reference(on), totalMinor(on)));
            Blocked webhookWait = awaitBlockedBy(h); // the webhook waits for high
            Future<MvcResult> f = ex.submit(() -> checkout(x, null, null));
            // the checkout's reservation now queues as well (behind the webhook or the holder)
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline && jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE cardinality(pg_blocking_pids(pid)) > 0 AND pid <> ?",
                    Integer.class, webhookWait.pid()) == 0) {
                Thread.sleep(20);
            }
            assertThat(f.isDone()).as("checkout queued for the product locks").isFalse();
            h.commit();
            xr = f.get(60, TimeUnit.SECONDS);
        } finally {
            ex.shutdownNow();
        }
        assertThat(answer.get(60, TimeUnit.SECONDS)).isEqualTo(200);
        awaitStatus(on, "COMPLETED");
        assertThat(xr.getResponse().getStatus()).as(xr.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(initiateCalls.get()).isEqualTo(2);
        assertThat(reservedQty(acme, low)).isEqualTo(1);
        assertThat(reservedQty(acme, high)).isEqualTo(1);
        assertThat(productInt(low, "sold_count")).isEqualTo(1);
        assertThat(productInt(high, "sold_count")).isEqualTo(1);
        assertThat(layerRemaining(acme, low)).isEqualTo(8);
        assertThat(layerRemaining(acme, high)).isEqualTo(8);
    }

    // ------------------------------------------------------------------------------ release / restock / checkout

    @Test
    void customerCancelReleasesUnderTheStockLock() throws Exception {
        long p = product(acme, "Rel", "50.00", 10);
        Customer c = customer(acme, "rel@acme.test");
        cart(c, p, 2);
        String on = placeOrder(c);
        MvcResult r;
        try (Holder h = new Holder(p)) {
            r = whileHeld(h, () -> cancel(c, on), () -> assertWaitsInTheStockLockBeforeAnyStockWrite(h));
        }
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(layerRemaining(acme, p)).isEqualTo(10);
        assertThat(reservedQty(acme, p)).isZero();
    }

    @Test
    void merchantCancelOfAPaidOrderRestocksUnderTheStockLock() throws Exception {
        long low = product(acme, "RsLow", "50.00", 10);
        long high = product(acme, "RsHigh", "60.00", 10);
        Customer c = customer(acme, "rs@acme.test");
        cart(c, high, 1);
        cart(c, low, 2);
        String on = placeOrder(c);
        pay(c, on);
        assertThat(productInt(low, "sold_count")).isEqualTo(2);
        MvcResult r;
        try (Holder h = new Holder(high)) {
            r = whileHeld(h, () -> asAdmin(put("/api/v1/order/update-order-status/" + on), Map.of("orderStatus", "CANCELLED")),
                    () -> assertWaitsInTheStockLockBeforeAnyStockWrite(h));
        }
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(layerRemaining(acme, low)).isEqualTo(10);
        assertThat(layerRemaining(acme, high)).isEqualTo(10);
        assertThat(productInt(low, "sold_count")).isZero();
        assertThat(productInt(high, "sold_count")).isZero();
    }

    @Test
    void multiProductCheckoutWaitsForEveryProductInTheOneLockStatement_notOnlyTheLowestId() throws Exception {
        // reviewer X14: a lock of only the first (lowest) id left the others to the stock refresh's UPDATE
        long low = product(acme, "MLow", "50.00", 10);
        long high = product(acme, "MHigh", "60.00", 10);
        Customer c = customer(acme, "m@acme.test");
        cart(c, low, 1);
        cart(c, high, 1);
        MvcResult r;
        try (Holder h = new Holder(high)) {
            r = whileHeld(h, () -> checkout(c, null, null), () -> assertWaitsInTheStockLockBeforeAnyStockWrite(h));
        }
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(reservedQty(acme, low)).isEqualTo(1);
        assertThat(reservedQty(acme, high)).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------ merchant stock writers

    @Test
    void receiptTakesTheStockLock() throws Exception {
        long p = product(acme, "Rcpt", "50.00", 0);
        MvcResult r;
        try (Holder h = new Holder(p)) {
            r = whileHeld(h, () -> asAdmin(post("/api/v1/admin/inventory/receipts"), Map.of("productId", p, "quantity", 5,
                    "unitCost", "40.00", "unitSellingPrice", "50.00", "reference", "RCPT-1")),
                    () -> assertWaitsInTheStockLockBeforeAnyStockWrite(h));
        }
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isBetween(200, 201);
        assertThat(layerRemaining(acme, p)).isEqualTo(5);
    }

    @Test
    void adjustmentDecreaseTakesTheStockLock() throws Exception {
        long p = product(acme, "Adj", "50.00", 10);
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("productId", p);
        req.put("direction", "DECREASE");
        req.put("quantity", 3);
        req.put("reason", "damaged");
        MvcResult r;
        try (Holder h = new Holder(p)) {
            r = whileHeld(h, () -> asAdmin(post("/api/v1/admin/inventory/adjustments"), req),
                    () -> assertWaitsInTheStockLockBeforeAnyStockWrite(h));
        }
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isBetween(200, 201);
        assertThat(layerRemaining(acme, p)).isEqualTo(7);
        assertThat(productInt(p, "stock_quantity")).isEqualTo(7);
    }

    @Test
    void walkInSaleTakesTheStockLockForAllItsProducts() throws Exception {
        long low = product(acme, "WiLow", "50.00", 10);
        long high = product(acme, "WiHigh", "60.00", 10);
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("items", List.of(Map.of("productId", high, "quantity", 1), Map.of("productId", low, "quantity", 2)));
        req.put("paymentMethod", "CASH");
        req.put("amountPaid", "100000.00");
        MvcResult r;
        try (Holder h = new Holder(high)) {
            r = whileHeld(h, () -> asAdmin(post("/api/v1/admin/walk-in/order"), req),
                    () -> assertWaitsInTheStockLockBeforeAnyStockWrite(h));
        }
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isBetween(200, 201);
        assertThat(layerRemaining(acme, low)).isEqualTo(8);
        assertThat(layerRemaining(acme, high)).isEqualTo(9);
        assertThat(productInt(low, "sold_count")).isEqualTo(2);
    }

    @Test
    void forwardConversionLocksSourceAndTargetBeforeConsumingTheSource() throws Exception {
        // reviewer X07: without the up-front lock the source was consumed (written) before the target was locked
        long[] f = family(5, 0);
        long bulk = f[0];
        long base = f[1];
        MvcResult r;
        try (Holder h = new Holder(base)) {
            r = whileHeld(h, () -> asAdmin(post("/api/v1/admin/stock-conversions/forward"),
                    Map.of("sourceProductId", bulk, "quantity", 2)), () -> assertWaitsInTheStockLockBeforeAnyStockWrite(h));
        }
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(layerRemaining(acme, bulk)).isEqualTo(3);
        assertThat(layerRemaining(acme, base)).isEqualTo(20);
    }

    @Test
    void reverseConversionLocksSourceAndTargetBeforeConsumingTheSource() throws Exception {
        // reviewer X09: locking only the source let the source be consumed before the target was locked
        long[] f = family(0, 30);
        long bulk = f[0];
        long base = f[1];
        MvcResult r;
        try (Holder h = new Holder(bulk)) {
            r = whileHeld(h, () -> asAdmin(post("/api/v1/admin/stock-conversions/reverse"),
                    Map.of("sourceProductId", base, "quantity", 20, "targetProductId", bulk)),
                    () -> assertWaitsInTheStockLockBeforeAnyStockWrite(h));
        }
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(layerRemaining(acme, base)).isEqualTo(10);
        assertThat(layerRemaining(acme, bulk)).isEqualTo(2);
    }

    // ------------------------------------------------------------------------- admin inventory GETs (reviewer R1)

    @Test
    void summaryAndHistoryGetsOfALegacyProductBootstrapUnderTheLock_neverASecondLegacyLayer() throws Exception {
        // reviewer R1: a stock writer holds the product and has just bootstrapped the legacy layer (not committed);
        // the GET used to bootstrap a second one without waiting -> stock 5 became 10
        for (String path : new String[]{"/summary", "/history"}) {
            long p = product(acme, "Legacy" + path.substring(1), "100.00", 5);
            MvcResult r;
            try (Holder h = new Holder(p)) {
                h.execute("INSERT INTO tenant_acme.inventory_layers (product_id, source_type, source_reference, note, "
                        + "received_quantity, remaining_quantity, unit_cost, unit_selling_price, currency_code, received_at, "
                        + "created_by, created_at) VALUES (" + p + ", 'LEGACY_BALANCE', 'LEGACY-" + p + "', 'x', 5, 5, 0, 100, "
                        + "'GHS', now(), 'System', now())");
                r = whileHeld(h, () -> asAdmin(get("/api/v1/admin/inventory/products/" + p + path), null),
                        () -> assertThat(awaitBlockedBy(h).query()).containsIgnoringCase("ORDER BY id FOR NO KEY UPDATE"));
            }
            assertThat(r.getResponse().getStatus()).as(path + " " + r.getResponse().getContentAsString()).isEqualTo(200);
            assertThat(legacyLayers(p)).as(path).isEqualTo(1);
            assertThat(layerRemaining(acme, p)).as(path).isEqualTo(5);
        }
    }

    @Test
    void summaryGetOfAProductThatAlreadyHasALayerDoesNotWaitForTheStockLock() throws Exception {
        long p = product(acme, "Layered", "100.00", 5);
        assertThat(asAdmin(get("/api/v1/admin/inventory/products/" + p + "/summary"), null).getResponse().getStatus())
                .isEqualTo(200); // bootstraps the layer
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try (Holder h = new Holder(p)) {
            // answered while another transaction still holds the product: a read takes no lock
            MvcResult r = ex.submit(() -> asAdmin(get("/api/v1/admin/inventory/products/" + p + "/summary"), null))
                    .get(30, TimeUnit.SECONDS);
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            r = ex.submit(() -> asAdmin(get("/api/v1/admin/inventory/products/" + p + "/history"), null))
                    .get(30, TimeUnit.SECONDS);
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
        } finally {
            ex.shutdownNow();
        }
        assertThat(legacyLayers(p)).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------- checkout pre-check / admin event

    @Test
    void stockPreCheckLooksAtEveryCartItem_whateverItsPosition() throws Exception {
        // reviewer X11: a pre-check of only the first item let an unservable cart reach Paystack
        long ok = product(acme, "Fine", "50.00", 10);
        long gone = product(acme, "Empty", "60.00", 0);
        Customer a = customer(acme, "pa@acme.test");
        cart(a, ok, 1);
        cart(a, gone, 1);
        Customer b = customer(acme, "pb@acme.test");
        cart(b, gone, 1);
        cart(b, ok, 1);
        for (Customer c : new Customer[]{a, b}) {
            MvcResult r = checkout(c, null, null);
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(400);
            assertThat(r.getResponse().getContentAsString()).contains("Insufficient stock for: Empty");
        }
        assertThat(initiateCalls.get()).as("no Paystack transaction for an unservable cart").isZero();
        assertThat(count(acme, "orders")).isZero();
    }

    @Test
    void theAdminNewOrderEventIsPublishedOnlyAfterTheReservation_aStockRaceLoserPublishesNone() throws Exception {
        // reviewer X16. ApplicationEvents records what the test thread (and threads it starts) publishes.
        long warm = product(acme, "Warm", "10.00", 5);
        Customer w = customer(acme, "warm@acme.test");
        cart(w, warm, 1);
        placeOrder(w);
        assertThat(events.stream(Order.class).count()).as("a successful checkout publishes the event").isEqualTo(1);
        events.clear();

        long p = product(acme, "Last", "100.00", 1);
        Customer winner = customer(acme, "win@acme.test");
        cart(winner, p, 1);
        Customer loser = customer(acme, "lose@acme.test");
        cart(loser, p, 1);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        java.util.concurrent.atomic.AtomicReference<String> winnerOrder = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            when(gateway.initiatePaystackTransaction(any())).thenAnswer(inv -> {
                PaystackInitiatePaymentRequest req = inv.getArgument(0);
                initiateCalls.incrementAndGet();
                if (req.email().equals(loser.email())) {
                    // while the loser is inside Paystack (pre-check passed, nothing reserved) the winner buys the last unit
                    MvcResult wr = ex.submit(() -> checkout(winner, null, null)).get(60, TimeUnit.SECONDS);
                    assertThat(wr.getResponse().getStatus()).isEqualTo(200);
                    winnerOrder.set(body(wr).at("/data/orderNumber").asText());
                }
                String code = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                return new PaystackInitiateTransactionResponse(true, "Authorization URL created",
                        new PaystackInitiateTransactionResponse.Data("https://checkout.paystack.com/" + code, code, "T" + code));
            });
            MvcResult lr = checkout(loser, null, null);
            assertThat(lr.getResponse().getStatus()).as(lr.getResponse().getContentAsString()).isEqualTo(400);
            assertThat(lr.getResponse().getContentAsString()).contains("Insufficient stock for: Last");
        } finally {
            ex.shutdownNow();
        }
        assertThat(winnerOrder.get()).isNotBlank();
        assertThat(events.stream(Order.class).map(Order::getOrderNumber).toList())
                .as("only the winner's new-order event; the stock-race loser published none")
                .containsExactly(winnerOrder.get());
        assertThat(count(acme, "orders")).isEqualTo(2);
        assertThat(reservedQty(acme, p)).isEqualTo(1);
    }
}
