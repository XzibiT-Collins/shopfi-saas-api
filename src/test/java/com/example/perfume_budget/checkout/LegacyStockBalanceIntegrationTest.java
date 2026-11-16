package com.example.perfume_budget.checkout;

import com.example.perfume_budget.dto.payment.response.PaystackVerifyTransactionResponse;
import com.example.perfume_budget.support.TestTenants;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * INV-01: a product that still carries only a legacy {@code products.stock_quantity} (no inventory layer yet) must keep
 * that balance, as exactly one LEGACY_BALANCE layer (with one LEGACY_BOOTSTRAP movement), whichever stock writer
 * touches it first; a receipt, an adjustment increase or a conversion adds on top of it instead of replacing it.
 *
 * <p>A zero legacy balance has nothing to preserve: no LEGACY_BALANCE layer and no zero-quantity LEGACY_BOOTSTRAP
 * movement is created for it (the same {@code > 0} rule as the V5 location-ledger backfill).
 *
 * <p>Products seeded with {@link #product} are legacy products: a stock_quantity and no layer.
 */
class LegacyStockBalanceIntegrationTest extends AbstractCheckoutIntegrationTest {

    private Cookie admin;

    @BeforeEach
    void adminLogin() throws Exception {
        admin = cookie("accessToken", setCookieValue(loginPassword("acme", acme.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken"));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private MvcResult asAdmin(MockHttpServletRequestBuilder b, Object content) throws Exception {
        b.header("X-Tenant-ID", acme.slug()).cookie(admin);
        if (content != null) b.contentType("application/json").content(json.writeValueAsString(content));
        return mvc.perform(b).andReturn();
    }

    private static void ok(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isBetween(200, 201);
    }

    private long legacyProduct(String name, int stock) {
        long p = product(acme, name, "100.00", stock);
        jdbc.update("UPDATE tenant_acme.products SET cost_price_amount = 60, cost_price_currency = 'GHS' WHERE id = ?", p);
        return p;
    }

    private MvcResult receipt(long p, int qty, String ref) throws Exception {
        return asAdmin(post("/api/v1/admin/inventory/receipts"), Map.of("productId", p, "quantity", qty,
                "unitCost", "40.00", "unitSellingPrice", "50.00", "reference", ref));
    }

    private MvcResult adjustment(long p, String direction, int qty) throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("productId", p);
        req.put("direction", direction);
        req.put("quantity", qty);
        req.put("reason", "count");
        if ("INCREASE".equals(direction)) {
            req.put("unitCost", "45.00");
            req.put("unitSellingPrice", "55.00");
        }
        return asAdmin(post("/api/v1/admin/inventory/adjustments"), req);
    }

    private int stock(long p) {
        return jdbc.queryForObject("SELECT stock_quantity FROM tenant_acme.products WHERE id = ?", Integer.class, p);
    }

    /** "SOURCE_TYPE received/remaining" per layer, oldest first (the FIFO order the service uses). */
    private List<String> layers(long p) {
        return jdbc.queryForList("SELECT source_type || ' ' || received_quantity || '/' || remaining_quantity "
                + "FROM tenant_acme.inventory_layers WHERE product_id = ? ORDER BY received_at, id", String.class, p);
    }

    /** "MOVEMENT_TYPE quantity" per movement, in the order they were written. */
    private List<String> movements(long p) {
        return jdbc.queryForList("SELECT movement_type || ' ' || quantity FROM tenant_acme.inventory_movements "
                + "WHERE product_id = ? ORDER BY id", String.class, p);
    }

    private int locationOnHand(long p) {
        return jdbc.queryForObject("SELECT coalesce(sum(quantity_on_hand), 0) FROM tenant_acme.location_stock WHERE product_id = ?",
                Integer.class, p);
    }

    /** Every layer's remaining quantity must be what its movements say: no phantom or missing movement. */
    private void assertLedgerReconciles(long p) {
        int fromMovements = jdbc.queryForObject("SELECT coalesce(sum(CASE WHEN movement_type IN ('LEGACY_BOOTSTRAP', 'RECEIPT', "
                + "'ADJUSTMENT_IN', 'CONVERSION_IN', 'RELEASE', 'RETURN') THEN quantity WHEN movement_type IN ('RESERVATION', "
                + "'ADJUSTMENT_OUT', 'CONVERSION_OUT') THEN -quantity WHEN movement_type = 'SALE' AND reference_type = 'WALK_IN_ORDER' "
                + "THEN -quantity ELSE 0 END), 0) FROM tenant_acme.inventory_movements WHERE product_id = ?", Integer.class, p);
        assertThat(layerRemaining(acme, p)).as("layers vs movements").isEqualTo(fromMovements);
        assertThat(stock(p)).as("product stock vs layers").isEqualTo(layerRemaining(acme, p));
    }

    private String placeOrder(Customer c) throws Exception {
        MvcResult r = checkout(c, null, null);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r).at("/data/orderNumber").asText();
    }

    private void pay(Customer c, String orderNumber) throws Exception {
        String ref = jdbc.queryForObject("SELECT p.provider_payment_reference FROM tenant_acme.payments p JOIN tenant_acme.orders o "
                + "ON o.id = p.order_id WHERE o.order_number = ?", String.class, orderNumber);
        long minor = jdbc.queryForObject("SELECT total_amount * 100 FROM tenant_acme.orders WHERE order_number = ?",
                BigDecimal.class, orderNumber).longValue();
        verifyAnswer = new PaystackVerifyTransactionResponse(true, "ok", new PaystackVerifyTransactionResponse.Data(
                "success", ref, BigDecimal.valueOf(minor), "GHS", "card", "Successful"));
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

    // ------------------------------------------------------------------------------- receipts (the reported bug)

    @Test
    void receiptOnALegacyProductAddsOnTopOfTheLegacyBalance() throws Exception {
        long p = legacyProduct("Legacy", 5);

        MvcResult r = receipt(p, 3, "PO-1");
        ok(r);
        assertThat(body(r).at("/data/stockQuantity").asInt()).as("response").isEqualTo(8);
        assertThat(body(r).at("/data/layers").size()).isEqualTo(2);
        assertThat(stock(p)).isEqualTo(8);
        assertThat(layers(p)).containsExactly("LEGACY_BALANCE 5/5", "PURCHASE 3/3");
        assertThat(movements(p)).containsExactly("LEGACY_BOOTSTRAP 5", "RECEIPT 3");
        // the legacy balance is already in the location ledger (V5 backfill): only the receipt moves it
        assertThat(locationOnHand(p)).isEqualTo(3);
        // the legacy layer keeps the legacy cost / price, the receipt layer its own
        assertThat(jdbc.queryForObject("SELECT unit_cost FROM tenant_acme.inventory_layers WHERE product_id = ? "
                + "AND source_type = 'LEGACY_BALANCE'", BigDecimal.class, p)).isEqualByComparingTo("60.00");

        // a second receipt and the admin GETs never bootstrap the balance again
        ok(receipt(p, 2, "PO-2"));
        ok(asAdmin(get("/api/v1/admin/inventory/products/" + p + "/summary"), null));
        ok(asAdmin(get("/api/v1/admin/inventory/products/" + p + "/history"), null));
        assertThat(stock(p)).isEqualTo(10);
        assertThat(layers(p)).containsExactly("LEGACY_BALANCE 5/5", "PURCHASE 3/3", "PURCHASE 2/2");
        assertThat(movements(p)).containsExactly("LEGACY_BOOTSTRAP 5", "RECEIPT 3", "RECEIPT 2");
        assertLedgerReconciles(p);
    }

    @Test
    void aBackdatedReceiptDoesNotJumpTheLegacyBalanceInFifoOrder() throws Exception {
        // the legacy units were on hand before any receipt; a receipt dated in the past must not be sold first
        long p = legacyProduct("Backdated", 5);
        ok(asAdmin(post("/api/v1/admin/inventory/receipts"), Map.of("productId", p, "quantity", 3, "unitCost", "40.00",
                "unitSellingPrice", "50.00", "reference", "PO-OLD", "receivedAt", LocalDateTime.now().minusYears(3).toString())));
        assertThat(layers(p)).containsExactly("LEGACY_BALANCE 5/5", "PURCHASE 3/3");
        assertThat(stock(p)).isEqualTo(8);

        ok(adjustment(p, "DECREASE", 6)); // FIFO: all 5 legacy units, then 1 received unit
        assertThat(layers(p)).containsExactly("LEGACY_BALANCE 5/0", "PURCHASE 3/2");
        assertThat(stock(p)).isEqualTo(2);
        assertLedgerReconciles(p);
    }

    @Test
    void receiptAndGetsOnAZeroStockLegacyProductCreateNoLegacyLayer_theReceiptIsTheOpeningStock() throws Exception {
        // decision pinned (API-05 L3): a zero legacy balance is not bootstrapped (needsLegacyLayer is "> 0", not ">= 0")
        long p = legacyProduct("Zero", 0);
        ok(asAdmin(get("/api/v1/admin/inventory/products/" + p + "/summary"), null));
        ok(asAdmin(get("/api/v1/admin/inventory/products/" + p + "/history"), null));
        assertThat(layers(p)).isEmpty();
        assertThat(movements(p)).isEmpty();

        ok(receipt(p, 3, "PO-Z"));
        assertThat(stock(p)).isEqualTo(3);
        assertThat(layers(p)).containsExactly("OPENING_STOCK 3/3");
        assertThat(movements(p)).containsExactly("RECEIPT 3");
        assertLedgerReconciles(p);
    }

    @Test
    void adjustmentIncreaseAndCheckoutOnAZeroStockLegacyProductCreateNoLegacyLayer() throws Exception {
        long p = legacyProduct("ZeroAdj", 0);
        Customer c = customer(acme, "zero@acme.test");
        cart(c, p, 1);
        MvcResult r = checkout(c, null, null);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(400);
        assertThat(adjustment(p, "DECREASE", 1).getResponse().getStatus()).isEqualTo(400);
        assertThat(layers(p)).isEmpty();
        assertThat(movements(p)).isEmpty();

        ok(adjustment(p, "INCREASE", 4));
        assertThat(layers(p)).containsExactly("ADJUSTMENT_IN 4/4");
        assertThat(movements(p)).containsExactly("ADJUSTMENT_IN 4");
        assertThat(stock(p)).isEqualTo(4);
    }

    // ------------------------------------------------------------------------------------- other stock writers

    @Test
    void adjustmentIncreaseOnALegacyProductAddsOnTopOfTheLegacyBalance() throws Exception {
        long p = legacyProduct("LegacyAdj", 5);
        MvcResult r = adjustment(p, "INCREASE", 2);
        ok(r);
        assertThat(body(r).at("/data/stockQuantity").asInt()).isEqualTo(7);
        assertThat(layers(p)).containsExactly("LEGACY_BALANCE 5/5", "ADJUSTMENT_IN 2/2");
        assertThat(movements(p)).containsExactly("LEGACY_BOOTSTRAP 5", "ADJUSTMENT_IN 2");
        assertLedgerReconciles(p);
    }

    @Test
    void adjustmentDecreaseOnALegacyProductConsumesTheLegacyBalance() throws Exception {
        long p = legacyProduct("LegacyDec", 10);
        ok(adjustment(p, "DECREASE", 3));
        assertThat(layers(p)).containsExactly("LEGACY_BALANCE 10/7");
        assertThat(movements(p)).containsExactly("LEGACY_BOOTSTRAP 10", "ADJUSTMENT_OUT 3");
        assertLedgerReconciles(p);
    }

    @Test
    void forwardConversionIntoALegacyTargetAddsOnTopOfTheTargetsLegacyBalance() throws Exception {
        long[] f = family(5, 7);
        long bulk = f[0];
        long base = f[1];
        MvcResult r = asAdmin(post("/api/v1/admin/stock-conversions/forward"), Map.of("sourceProductId", bulk, "quantity", 2));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(stock(bulk)).isEqualTo(3);
        assertThat(stock(base)).as("7 legacy + 20 converted").isEqualTo(27);
        assertThat(layers(bulk)).containsExactly("LEGACY_BALANCE 5/3");
        assertThat(movements(bulk)).containsExactly("LEGACY_BOOTSTRAP 5", "CONVERSION_OUT 2");
        assertThat(layers(base)).hasSize(2).first().isEqualTo("LEGACY_BALANCE 7/7");
        assertThat(movements(base)).containsExactly("LEGACY_BOOTSTRAP 7", "CONVERSION_IN 20");
        assertLedgerReconciles(bulk);
        assertLedgerReconciles(base);
    }

    @Test
    void reverseConversionIntoALegacyTargetAddsOnTopOfTheTargetsLegacyBalance() throws Exception {
        long[] f = family(1, 30);
        long bulk = f[0];
        long base = f[1];
        MvcResult r = asAdmin(post("/api/v1/admin/stock-conversions/reverse"),
                Map.of("sourceProductId", base, "quantity", 20, "targetProductId", bulk));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(stock(base)).isEqualTo(10);
        assertThat(stock(bulk)).as("1 legacy + 2 converted").isEqualTo(3);
        assertThat(layers(bulk)).hasSize(2).first().isEqualTo("LEGACY_BALANCE 1/1");
        assertThat(movements(bulk)).containsExactly("LEGACY_BOOTSTRAP 1", "CONVERSION_IN 2");
        assertThat(movements(base)).containsExactly("LEGACY_BOOTSTRAP 30", "CONVERSION_OUT 20");
        assertLedgerReconciles(bulk);
        assertLedgerReconciles(base);
    }

    @Test
    void walkInSaleOfALegacyProductConsumesTheLegacyBalance() throws Exception {
        long p = legacyProduct("LegacyWalkIn", 10);
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("items", List.of(Map.of("productId", p, "quantity", 2)));
        req.put("paymentMethod", "CASH");
        req.put("amountPaid", "100000.00");
        ok(asAdmin(post("/api/v1/admin/walk-in/order"), req));
        assertThat(layers(p)).containsExactly("LEGACY_BALANCE 10/8");
        assertThat(movements(p)).containsExactly("LEGACY_BOOTSTRAP 10", "SALE 2");
        assertLedgerReconciles(p);
    }

    @Test
    void checkoutReservationAndCustomerCancelOfALegacyProductKeepTheBalance() throws Exception {
        long p = legacyProduct("LegacyRes", 5);
        Customer c = customer(acme, "lres@acme.test");
        cart(c, p, 2);
        String on = placeOrder(c);
        assertThat(layers(p)).containsExactly("LEGACY_BALANCE 5/3");
        assertThat(stock(p)).isEqualTo(3);

        MvcResult r = cancel(c, on);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(layers(p)).containsExactly("LEGACY_BALANCE 5/5");
        assertThat(movements(p)).containsExactly("LEGACY_BOOTSTRAP 5", "RESERVATION 2", "RELEASE 2");
        assertLedgerReconciles(p);
    }

    @Test
    void paidOrderOfALegacyProductThenMerchantCancelRestocksOntoTheLegacyLayer() throws Exception {
        long p = legacyProduct("LegacyRestock", 5);
        Customer c = customer(acme, "lrs@acme.test");
        cart(c, p, 2);
        String on = placeOrder(c);
        pay(c, on);
        ok(receipt(p, 4, "PO-AFTER-SALE")); // a receipt after the bootstrap adds normally
        assertThat(stock(p)).isEqualTo(7);

        ok(asAdmin(put("/api/v1/order/update-order-status/" + on), Map.of("orderStatus", "CANCELLED")));
        assertThat(layers(p)).containsExactly("LEGACY_BALANCE 5/5", "PURCHASE 4/4");
        assertThat(movements(p)).containsExactly("LEGACY_BOOTSTRAP 5", "RESERVATION 2", "SALE 2", "RECEIPT 4", "RETURN 2");
        assertThat(stock(p)).isEqualTo(9);
        assertLedgerReconciles(p);
    }

    // --------------------------------------------------------------------------------- receipt racing a checkout

    /** A transaction on its own connection holding a product row FOR NO KEY UPDATE until it is committed / closed. */
    private final class Holder implements AutoCloseable {
        final Connection conn;
        final int pid;

        Holder(long productId) throws SQLException {
            conn = jdbc.getDataSource().getConnection();
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SELECT id FROM tenant_acme.products WHERE id = " + productId + " FOR NO KEY UPDATE");
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

    /** Backends currently waiting on a lock (other than the holder), oldest wait first. */
    private List<Integer> waitingBackends(Holder h) {
        return jdbc.queryForList("SELECT pid FROM pg_stat_activity WHERE cardinality(pg_blocking_pids(pid)) > 0 "
                + "AND pid <> ? AND datname = current_database() ORDER BY state_change", Integer.class, h.pid);
    }

    /** Waits until {@code n} backends wait on a lock, each in the ProductStockLocks statement with no stock row written. */
    private void awaitWaitingInTheStockLock(Holder h, int n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && waitingBackends(h).size() < n) {
            Thread.sleep(20);
        }
        List<Integer> pids = waitingBackends(h);
        if (pids.size() < n) fail("expected " + n + " backends waiting for the product lock, saw " + pids.size());
        for (int pid : pids) {
            assertThat(jdbc.queryForObject("SELECT query FROM pg_stat_activity WHERE pid = ?", String.class, pid))
                    .containsIgnoringCase("ORDER BY id FOR NO KEY UPDATE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_locks l JOIN pg_class c ON c.oid = l.relation "
                    + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE l.pid = ? AND l.mode = 'RowExclusiveLock' "
                    + "AND n.nspname = 'tenant_acme' AND c.relname IN ('products', 'inventory_layers', 'inventory_allocations', "
                    + "'inventory_movements')", Integer.class, pid)).as("stock rows written before the lock").isZero();
        }
    }

    private void raceReceiptAndCheckout(boolean receiptFirst) throws Exception {
        long p = legacyProduct("Race" + receiptFirst, 5);
        Customer c = customer(acme, "race" + receiptFirst + "@acme.test");
        cart(c, p, 1);
        ExecutorService ex = Executors.newFixedThreadPool(2);
        MvcResult receiptResult;
        MvcResult checkoutResult;
        try (Holder h = new Holder(p)) {
            Future<MvcResult> first = ex.submit(() -> receiptFirst ? receipt(p, 3, "PO-RACE") : checkout(c, null, null));
            awaitWaitingInTheStockLock(h, 1);
            Future<MvcResult> second = ex.submit(() -> receiptFirst ? checkout(c, null, null) : receipt(p, 3, "PO-RACE"));
            awaitWaitingInTheStockLock(h, 2);
            assertThat(first.isDone() || second.isDone()).as("both wait for the held product").isFalse();
            h.conn.commit();
            receiptResult = (receiptFirst ? first : second).get(60, TimeUnit.SECONDS);
            checkoutResult = (receiptFirst ? second : first).get(60, TimeUnit.SECONDS);
        } finally {
            ex.shutdownNow();
        }
        ok(receiptResult);
        assertThat(checkoutResult.getResponse().getStatus()).as(checkoutResult.getResponse().getContentAsString()).isEqualTo(200);

        // whichever got the lock first, the legacy 5 is bootstrapped once by it, and nothing is doubled or lost
        assertThat(stock(p)).as("5 legacy + 3 received - 1 reserved").isEqualTo(7);
        assertThat(layerRemaining(acme, p)).isEqualTo(7);
        assertThat(reservedQty(acme, p)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.inventory_layers WHERE product_id = ? "
                + "AND source_type = 'LEGACY_BALANCE' AND received_quantity = 5", Integer.class, p)).isEqualTo(1);
        // the lock queue is FIFO, so the operations ran in the order they were started: pin it, it is what the case is about
        assertThat(movements(p)).containsExactlyElementsOf(receiptFirst
                ? List.of("LEGACY_BOOTSTRAP 5", "RECEIPT 3", "RESERVATION 1")
                : List.of("LEGACY_BOOTSTRAP 5", "RESERVATION 1", "RECEIPT 3"));
        assertLedgerReconciles(p);
    }

    @Test
    void receiptRacingACheckoutOnALegacyProduct_receiptGetsTheLockFirst() throws Exception {
        raceReceiptAndCheckout(true);
    }

    @Test
    void receiptRacingACheckoutOnALegacyProduct_checkoutGetsTheLockFirst() throws Exception {
        raceReceiptAndCheckout(false);
    }
}
