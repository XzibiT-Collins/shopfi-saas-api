package com.example.perfume_budget.walkin;

import com.example.perfume_budget.checkout.AbstractCheckoutIntegrationTest;
import com.example.perfume_budget.service.interfaces.InventoryManagementService;
import com.example.perfume_budget.support.TestTenant;
import com.example.perfume_budget.support.TestTenants;
import com.example.perfume_budget.tenancy.TenantSchemaMigrator;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * API-RD-03 AC3: walk-in cancel and refund. Real app + Postgres, tenants acme and bravo. Every test checks the effects
 * that matter in money and stock terms (layers, movements, sold count, ledger balances, journal entries, audit row), not
 * only the HTTP answer.
 */
@ExtendWith(OutputCaptureExtension.class)
class WalkInCancelRefundIntegrationTest extends AbstractCheckoutIntegrationTest {

    private static final String ORDER = "/api/v1/admin/walk-in/order";
    private static final String REASON = "Customer changed their mind";

    @Autowired private InventoryManagementService inventory;
    @Autowired private TenantSchemaMigrator migrator;
    @Autowired private com.example.perfume_budget.repository.InventoryMovementRepository movementRepository;

    private Cookie acmeAdmin;
    private Cookie bravoAdmin;
    private List<Long> deactivatedTaxes = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        acmeAdmin = cookie("accessToken", setCookieValue(loginPassword("acme", acme.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken"));
        bravoAdmin = cookie("accessToken", setCookieValue(loginPassword("bravo", bravo.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken"));
        deactivatedTaxes = new ArrayList<>(jdbc.queryForList("SELECT id FROM tenant_acme.taxes WHERE is_active", Long.class));
        jdbc.update("UPDATE tenant_acme.taxes SET is_active = false");
        jdbc.update("INSERT INTO tenant_acme.taxes (name, code, rate, is_active, is_compound, apply_order, created_at) "
                + "VALUES ('Test VAT', 'WCR-VAT', 10.00, true, false, 1, now())");
    }

    @AfterEach
    void cleanWalkIn() {
        for (String schema : new String[]{"tenant_acme", "tenant_bravo"}) {
            jdbc.execute("TRUNCATE " + schema + ".walk_in_orders, " + schema + ".walk_in_customers CASCADE");
        }
        jdbc.update("DELETE FROM tenant_acme.taxes WHERE code = 'WCR-VAT'");
        for (Long id : deactivatedTaxes) jdbc.update("UPDATE tenant_acme.taxes SET is_active = true WHERE id = ?", id);
        jdbc.update("DELETE FROM tenant_acme.front_desk_user_permission_overrides");
    }

    // ------------------------------------------------------------------------------------------ helpers

    private MvcResult send(MockHttpServletRequestBuilder b, Cookie who, TestTenant t, Object content, String key) throws Exception {
        b.header("X-Tenant-ID", t.slug()).cookie(who);
        if (key != null) b.header("Idempotency-Key", key);
        if (content != null) b.contentType("application/json").content(json.writeValueAsString(content));
        return mvc.perform(b).andReturn();
    }

    private Map<String, Object> sale(long productId, int qty) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", List.of(Map.of("productId", productId, "quantity", qty)));
        m.put("paymentMethod", "CASH");
        m.put("amountPaid", "1000.00");
        return m;
    }

    /** Places a sale as the acme admin and returns its order number. */
    private String sell(long productId, int qty) throws Exception {
        MvcResult r = send(post(ORDER), acmeAdmin, acme, sale(productId, qty), null);
        status(r, 200);
        return body(r).at("/data/orderNumber").asText();
    }

    private MvcResult cancel(Cookie who, TestTenant t, String number, String key, String reason) throws Exception {
        return send(post("/api/v1/admin/walk-in/orders/" + number + "/cancel"), who, t, Map.of("reason", reason), key);
    }

    private MvcResult refund(Cookie who, TestTenant t, String number, String key, String reason) throws Exception {
        return send(post("/api/v1/admin/walk-in/orders/" + number + "/refund"), who, t, Map.of("reason", reason), key);
    }

    private MvcResult cancel(String number, String key) throws Exception {
        return cancel(acmeAdmin, acme, number, key, REASON);
    }

    private MvcResult refund(String number, String key) throws Exception {
        return refund(acmeAdmin, acme, number, key, REASON);
    }

    private static void status(MvcResult r, int expected) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expected);
    }

    private static String code(MvcResult r, JsonNode body) {
        return body.at("/data/code").asText();
    }

    private int stock(long p) {
        return jdbc.queryForObject("SELECT stock_quantity FROM tenant_acme.products WHERE id = ?", Integer.class, p);
    }

    private int sold(long p) {
        return jdbc.queryForObject("SELECT sold_count FROM tenant_acme.products WHERE id = ?", Integer.class, p);
    }

    private String orderStatusOf(String number) {
        return jdbc.queryForObject("SELECT status FROM tenant_acme.walk_in_orders WHERE order_number = ?", String.class, number);
    }

    private int movements(String number, String type) {
        return jdbc.queryForObject("SELECT coalesce(sum(quantity),0) FROM tenant_acme.inventory_movements WHERE reference_type = 'WALK_IN_ORDER' "
                + "AND reference_id = ? AND movement_type = ?", Integer.class, number, type);
    }

    private int entries(String number, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM tenant_acme.journal_entries WHERE reference_type = 'WALK_IN_ORDER' AND reference_id = ? AND type = ?",
                Integer.class, number, type);
    }

    private int auditRows(String number) {
        return jdbc.queryForObject("SELECT count(*) FROM tenant_acme.walk_in_order_audit_events a JOIN tenant_acme.walk_in_orders o ON o.id = a.walk_in_order_id "
                + "WHERE o.order_number = ?", Integer.class, number);
    }

    /** Balance of every ledger account, as one comparable string. */
    private String balances() {
        return jdbc.queryForList("SELECT category || '=' || balance FROM tenant_acme.ledger_accounts ORDER BY id", String.class).toString();
    }

    /** Everything a reversal touches, as one comparable string. */
    private String snapshot(long p) {
        return String.join("|", "layers=" + layerRemaining(acme, p), "stock=" + stock(p), "sold=" + sold(p),
                "movements=" + count(acme, "inventory_movements"), "journal=" + count(acme, "journal_entries"),
                "lines=" + count(acme, "journal_entry_lines"), "audit=" + count(acme, "walk_in_order_audit_events"),
                "transfers=" + count(acme, "stock_transfers"), balances());
    }

    private Cookie frontDesk(String email, boolean allPermissions) {
        Long id = jdbc.queryForObject("INSERT INTO tenant_acme.users (full_name, email, password, roles, is_active, email_verified, created_at) "
                + "VALUES ('Desk', ?, 'x', 'FRONT_DESK', true, true, now()) RETURNING id", Long.class, email);
        if (allPermissions) {
            for (String perm : new String[]{"WALK_IN_ORDER_CREATE", "WALK_IN_ORDER_VIEW", "WALK_IN_ORDER_MARK_RECEIPT_PRINTED", "CUSTOMER_SEARCH",
                    "PRODUCT_VIEW_ADMIN_CATALOG", "PRODUCT_VIEW_STOCK_SUMMARY", "STOCK_TRANSFER", "STOCK_CONVERSION"}) {
                jdbc.update("INSERT INTO tenant_acme.front_desk_user_permission_overrides (user_id, permission, allowed, created_at) VALUES (?, ?, true, now())", id, perm);
            }
        }
        return cookie("accessToken", tenants.runAs(acme, () -> jwtUtil.generateAccessToken(email, "FRONT_DESK", id, "Desk")));
    }

    // ------------------------------------------------------------------------------------------ effects

    @Test
    void cancelPutsStockBackOnceReversesTheSaleExactlyAndWritesTheAuditRow() throws Exception {
        long p = product(acme, "Cancel Perfume", "50.00", 10);
        long q = product(acme, "Cancel Second", "20.00", 5);
        String balancesBefore = balances();
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("items", List.of(Map.of("productId", p, "quantity", 3), Map.of("productId", q, "quantity", 2)));
        req.put("paymentMethod", "CASH");
        req.put("amountPaid", "1000.00");
        MvcResult placed = send(post(ORDER), acmeAdmin, acme, req, null);
        status(placed, 200);
        String number = body(placed).at("/data/orderNumber").asText();
        assertThat(stock(p)).isEqualTo(7);
        assertThat(stock(q)).isEqualTo(3);
        assertThat(balances()).isNotEqualTo(balancesBefore);

        MvcResult r = cancel(number, "cancel-key-0001");
        status(r, 200);
        JsonNode d = body(r).get("data");
        assertThat(d.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(d.get("idempotentReplay").asBoolean()).isFalse();
        assertThat(d.get("orderNumber").asText()).isEqualTo(number);

        // stock: layers, product stock and sold count are back; the RETURN movements equal the SALE movements
        assertThat(stock(p)).isEqualTo(10);
        assertThat(stock(q)).isEqualTo(5);
        assertThat(layerRemaining(acme, p)).isEqualTo(10);
        assertThat(layerRemaining(acme, q)).isEqualTo(5);
        assertThat(sold(p)).isZero();
        assertThat(sold(q)).isZero();
        assertThat(movements(number, "SALE")).isEqualTo(5);
        assertThat(movements(number, "RETURN")).isEqualTo(5);
        // ledger: one reversal entry, balanced, and every account is back to where it was before the sale
        assertThat(entries(number, "WALK_IN_SALE")).isEqualTo(1);
        assertThat(entries(number, "WALK_IN_REVERSAL")).isEqualTo(1);
        assertThat(balances()).as("the books net to zero for the cancelled sale").isEqualTo(balancesBefore);
        assertThat(jdbc.queryForObject("SELECT sum(CASE WHEN l.entry_type = 'DEBIT' THEN l.amount ELSE -l.amount END) FROM tenant_acme.journal_entry_lines l "
                + "JOIN tenant_acme.journal_entries j ON j.id = l.journal_entry_id WHERE j.reference_id = ? AND j.type = 'WALK_IN_REVERSAL'", BigDecimal.class, number))
                .isEqualByComparingTo("0");
        // audit
        Map<String, Object> audit = jdbc.queryForMap("SELECT a.* FROM tenant_acme.walk_in_order_audit_events a JOIN tenant_acme.walk_in_orders o ON o.id = a.walk_in_order_id WHERE o.order_number = ?", number);
        assertThat(audit).containsEntry("event_type", "CANCELLED").containsEntry("from_status", "COMPLETED")
                .containsEntry("to_status", "CANCELLED").containsEntry("actor_email", acme.adminEmail())
                .containsEntry("reason", REASON).containsEntry("idempotency_key", "cancel-key-0001");
        assertThat((BigDecimal) audit.get("refund_amount")).isEqualByComparingTo(jdbc.queryForObject(
                "SELECT total_amount FROM tenant_acme.walk_in_orders WHERE order_number = ?", BigDecimal.class, number));
        assertThat(orderStatusOf(number)).isEqualTo("CANCELLED");
    }

    @Test
    void refundIsTheSameReversalWithItsOwnStatusAndAuditType() throws Exception {
        long p = product(acme, "Refund Perfume", "50.00", 10);
        String balancesBefore = balances();
        String number = sell(p, 4);

        MvcResult r = refund(number, "refund-key-0001");
        status(r, 200);
        assertThat(body(r).at("/data/status").asText()).isEqualTo("REFUNDED");
        assertThat(stock(p)).isEqualTo(10);
        assertThat(layerRemaining(acme, p)).isEqualTo(10);
        assertThat(sold(p)).isZero();
        assertThat(entries(number, "WALK_IN_REVERSAL")).isEqualTo(1);
        assertThat(balances()).isEqualTo(balancesBefore);
        assertThat(jdbc.queryForObject("SELECT a.event_type FROM tenant_acme.walk_in_order_audit_events a JOIN tenant_acme.walk_in_orders o ON o.id = a.walk_in_order_id "
                + "WHERE o.order_number = ?", String.class, number)).isEqualTo("REFUNDED");
        assertThat(orderStatusOf(number)).isEqualTo("REFUNDED");
    }

    @Test
    void theLocationLedgerGetsTheUnitsBackWhereTheyLeft() throws Exception {
        long p = product(acme, "Located Perfume", "50.00", 10);
        List<Long> priorSources = jdbc.queryForList("SELECT id FROM tenant_acme.storage_locations WHERE is_walk_in_sale_source", Long.class);
        jdbc.update("UPDATE tenant_acme.storage_locations SET is_walk_in_sale_source = false");
        Long location = jdbc.queryForObject("INSERT INTO tenant_acme.storage_locations (name, type, active, is_walk_in_sale_source, is_ecommerce_fulfilment_source, is_default_receiving, version, created_at) "
                + "VALUES ('WCR Floor', 'SHOP_FLOOR', true, true, false, false, 0, now()) RETURNING id", Long.class);
        try {
            String number = sell(p, 3);
            assertThat(jdbc.queryForObject("SELECT quantity_on_hand FROM tenant_acme.location_stock WHERE product_id = ? AND location_id = ?", Integer.class, p, location)).isEqualTo(-3);
            status(cancel(number, "cancel-key-0002"), 200);
            assertThat(jdbc.queryForObject("SELECT quantity_on_hand FROM tenant_acme.location_stock WHERE product_id = ? AND location_id = ?", Integer.class, p, location)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.stock_transfers WHERE reference_id = ? AND transfer_type = 'SALE_RETURN' AND to_location_id = ?",
                    Integer.class, number, location)).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM tenant_acme.stock_transfers WHERE from_location_id = ? OR to_location_id = ?", location, location);
            jdbc.update("DELETE FROM tenant_acme.location_stock WHERE location_id = ?", location);
            jdbc.update("DELETE FROM tenant_acme.storage_locations WHERE id = ?", location);
            for (Long id : priorSources) jdbc.update("UPDATE tenant_acme.storage_locations SET is_walk_in_sale_source = true WHERE id = ?", id);
        }
    }

    @Test
    void restoringTwiceNeverPutsStockBackTwice() throws Exception {
        long p = product(acme, "Restore Twice", "50.00", 10);
        String number = sell(p, 3);
        status(cancel(number, "cancel-key-0003"), 200);
        String after = snapshot(p);
        int again = tenants.runAs(acme, () -> inventory.restoreWalkInInventory(number));
        assertThat(again).as("everything was already returned").isZero();
        assertThat(snapshot(p)).isEqualTo(after);
        assertThat(stock(p)).isEqualTo(10);
    }

    // ------------------------------------------------------------------------------------------ replay and keys

    @Test
    void sameKeyReplaysWithoutASecondEffectAndAnotherBodyOnTheKeyIs409() throws Exception {
        long p = product(acme, "Replay Perfume", "50.00", 10);
        String number = sell(p, 2);
        status(cancel(number, "replay-key-0001"), 200);
        String after = snapshot(p);

        MvcResult again = cancel(acmeAdmin, acme, number, "replay-key-0001", "  " + REASON + "  ");
        status(again, 200);
        assertThat(body(again).at("/data/idempotentReplay").asBoolean()).isTrue();
        assertThat(body(again).at("/data/status").asText()).isEqualTo("CANCELLED");
        assertThat(snapshot(p)).as("replay: no second stock return, journal entry or audit row").isEqualTo(after);

        MvcResult otherReason = cancel(acmeAdmin, acme, number, "replay-key-0001", "a different reason");
        status(otherReason, 409);
        assertThat(code(otherReason, body(otherReason))).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        MvcResult otherAction = refund(number, "replay-key-0001");
        status(otherAction, 409);
        assertThat(code(otherAction, body(otherAction))).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(snapshot(p)).isEqualTo(after);
    }

    @Test
    void theSameKeyOnTwoDifferentOrdersIsTwoIndependentRequests() throws Exception {
        long p = product(acme, "Two Orders", "50.00", 10);
        String a = sell(p, 1);
        String b = sell(p, 2);
        status(cancel(a, "shared-order-key-1"), 200);
        MvcResult second = cancel(b, "shared-order-key-1");
        status(second, 200);
        assertThat(body(second).at("/data/idempotentReplay").asBoolean()).as("another order: not a replay").isFalse();
        assertThat(orderStatusOf(a)).isEqualTo("CANCELLED");
        assertThat(orderStatusOf(b)).isEqualTo("CANCELLED");
        assertThat(stock(p)).isEqualTo(10);
        assertThat(movements(b, "RETURN")).isEqualTo(2);
    }

    @Test
    void theKeyIsMandatoryAndMustBeWellFormed() throws Exception {
        long p = product(acme, "Key Perfume", "50.00", 10);
        String number = sell(p, 1);
        String before = snapshot(p);
        MvcResult none = cancel(number, null);
        status(none, 400);
        assertThat(code(none, body(none))).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        status(refund(number, null), 400);
        for (String bad : new String[]{"short", "has space in it!", "x".repeat(65)}) {
            MvcResult r = cancel(number, bad);
            status(r, 400);
            assertThat(code(r, body(r))).as(bad).isEqualTo("IDEMPOTENCY_KEY_INVALID");
        }
        assertThat(snapshot(p)).isEqualTo(before);
        assertThat(orderStatusOf(number)).isEqualTo("COMPLETED");
    }

    @Test
    void theBodyIsValidatedAndPartialRefundsAreRefusedNotSilentlyFull() throws Exception {
        long p = product(acme, "Body Perfume", "50.00", 10);
        String number = sell(p, 2);
        String before = snapshot(p);
        String url = "/api/v1/admin/walk-in/orders/" + number + "/refund";
        status(send(post(url), acmeAdmin, acme, Map.of("reason", "   "), "body-key-0001"), 400);
        status(send(post(url), acmeAdmin, acme, Map.of(), "body-key-0001"), 400);
        status(send(post(url), acmeAdmin, acme, Map.of("reason", "x".repeat(256)), "body-key-0001"), 400);
        status(send(post(url), acmeAdmin, acme, null, "body-key-0001"), 400);
        status(send(post(url), acmeAdmin, acme, Map.of("reason", REASON, "amount", "10.00"), "body-key-0001"), 400);
        assertThat(snapshot(p)).isEqualTo(before);
        assertThat(orderStatusOf(number)).isEqualTo("COMPLETED");
        // a refund is for the whole sale, whatever the cancel-style body: success only for a plain reason
        status(send(post(url), acmeAdmin, acme, Map.of("reason", REASON), "body-key-0001"), 200);
        assertThat(stock(p)).isEqualTo(10);
    }

    @Test
    void anUnknownOrderIs404() throws Exception {
        status(cancel("WI-00000000-9999", "missing-key-0001"), 404);
        status(refund("WI-00000000-9999", "missing-key-0001"), 404);
    }

    // ------------------------------------------------------------------------------------------ state machine

    @Test
    void onlyACompletedOrderCanBeReversedAndTerminalStatesAreTerminal() throws Exception {
        long p = product(acme, "State Perfume", "50.00", 20);
        String c = sell(p, 2);
        String r = sell(p, 3);
        status(cancel(c, "state-key-c-01"), 200);
        status(refund(r, "state-key-r-01"), 200);
        String after = snapshot(p);

        MvcResult cancelCancelled = cancel(c, "state-key-c-02");
        status(cancelCancelled, 409);
        assertThat(code(cancelCancelled, body(cancelCancelled))).isEqualTo("WALK_IN_ORDER_NOT_CANCELLABLE");
        assertThat(body(cancelCancelled).at("/data/currentStatus").asText()).isEqualTo("CANCELLED");
        MvcResult refundCancelled = refund(c, "state-key-c-03");
        status(refundCancelled, 409);
        assertThat(code(refundCancelled, body(refundCancelled))).isEqualTo("WALK_IN_ORDER_NOT_REFUNDABLE");
        MvcResult cancelRefunded = cancel(r, "state-key-r-02");
        status(cancelRefunded, 409);
        assertThat(body(cancelRefunded).at("/data/currentStatus").asText()).isEqualTo("REFUNDED");
        status(refund(r, "state-key-r-03"), 409);

        assertThat(snapshot(p)).as("rejected transitions change nothing").isEqualTo(after);
        assertThat(stock(p)).isEqualTo(20);
        assertThat(orderStatusOf(c)).isEqualTo("CANCELLED");
        assertThat(orderStatusOf(r)).isEqualTo("REFUNDED");
        assertThat(auditRows(c)).isEqualTo(1);
        assertThat(auditRows(r)).isEqualTo(1);
    }

    @Test
    void aReplayOfTheSaleKeyAfterACancelShowsCancelledAndNeverSellsAgain() throws Exception {
        long p = product(acme, "Sale Replay", "50.00", 10);
        MvcResult placed = send(post(ORDER), acmeAdmin, acme, sale(p, 2), "sale-key-0001");
        status(placed, 200);
        String number = body(placed).at("/data/orderNumber").asText();
        status(cancel(number, "cancel-key-0004"), 200);
        String after = snapshot(p);
        MvcResult replay = send(post(ORDER), acmeAdmin, acme, sale(p, 2), "sale-key-0001");
        status(replay, 200);
        assertThat(body(replay).at("/data/idempotentReplay").asBoolean()).isTrue();
        assertThat(body(replay).at("/data/status").asText()).isEqualTo("CANCELLED");
        assertThat(snapshot(p)).isEqualTo(after);
        assertThat(count(acme, "walk_in_orders")).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------ money safety

    @Test
    void notEnoughCashToPayBackIs409AndNothingChanges() throws Exception {
        long p = product(acme, "Cash Perfume", "50.00", 10);
        String number = sell(p, 2);
        jdbc.update("UPDATE tenant_acme.ledger_accounts SET balance = 0 WHERE category = 'CASH'");
        String before = snapshot(p);
        MvcResult r = cancel(number, "cash-key-0001");
        status(r, 409);
        assertThat(code(r, body(r))).isEqualTo("INSUFFICIENT_CASH");
        assertThat(snapshot(p)).as("stock restore, entry and status rolled back together").isEqualTo(before);
        assertThat(orderStatusOf(number)).isEqualTo("COMPLETED");
        assertThat(auditRows(number)).isZero();
    }

    @Test
    void aLedgerFaultRollsBackTheWholeCancelAndTheKeyCanBeRetried() throws Exception {
        long p = product(acme, "Fault Perfume", "50.00", 10);
        String number = sell(p, 2);
        // a stray reversal entry makes the ledger refuse a second one AFTER the stock was already put back
        Long strayId = jdbc.queryForObject("INSERT INTO tenant_acme.journal_entries (entry_number, description, type, reference_type, reference_id, is_manual, recorded_by, transaction_date, created_at) "
                + "VALUES ('WCR-STRAY', 'stray', 'WALK_IN_REVERSAL', 'WALK_IN_ORDER', ?, false, 'test', now(), now()) RETURNING id", Long.class, number);
        String before = snapshot(p);
        MvcResult r = cancel(number, "fault-key-0001");
        status(r, 500);
        assertThat(code(r, body(r))).isEqualTo("ACCOUNTING_ERROR");
        assertThat(orderStatusOf(number)).isEqualTo("COMPLETED");
        assertThat(snapshot(p)).as("stock, movements, entry, status and audit rolled back together").isEqualTo(before);
        assertThat(movements(number, "RETURN")).isZero();

        jdbc.update("DELETE FROM tenant_acme.journal_entries WHERE id = ?", strayId);
        status(cancel(number, "fault-key-0001"), 200);
        assertThat(stock(p)).isEqualTo(10);
        assertThat(entries(number, "WALK_IN_REVERSAL")).isEqualTo(1);
    }

    @Test
    void theAuditTableIsAppendOnlyAndTheReasonIsNotLogged(CapturedOutput out) throws Exception {
        long p = product(acme, "Audit Perfume", "50.00", 10);
        String number = sell(p, 1);
        status(cancel(acmeAdmin, acme, number, "audit-key-0001", "very-secret-reason-text"), 200);
        assertThat(out.getAll()).doesNotContain("very-secret-reason-text");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("UPDATE tenant_acme.walk_in_order_audit_events SET reason = 'tampered'"))
                .hasMessageContaining("append-only");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("DELETE FROM tenant_acme.walk_in_order_audit_events"))
                .hasMessageContaining("append-only");
        // the database also refuses a second terminal event for one order (backstop of exactly-once)
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO tenant_acme.walk_in_order_audit_events (walk_in_order_id, event_type, from_status, to_status, actor_email, reason, refund_amount, "
                        + "idempotency_key, request_fingerprint, created_at) SELECT id, 'REFUNDED', 'COMPLETED', 'REFUNDED', 'x', 'x', 1, 'other-key-9999', 'f', now() "
                        + "FROM tenant_acme.walk_in_orders WHERE order_number = ?", number))
                .hasMessageContaining("uq_walk_in_audit_one_event_per_order");
    }

    @Test
    void cancelledSalesLeaveTheItemsSoldReport() throws Exception {
        long p = product(acme, "Report Perfume Zq", "50.00", 10);
        String number = sell(p, 2);
        String url = "/api/v1/admin/inventory/sales/items-sold?source=WALK_IN";
        assertThat(send(get(url), acmeAdmin, acme, null, null).getResponse().getContentAsString()).contains("Report Perfume Zq");
        status(cancel(number, "report-key-0001"), 200);
        assertThat(send(get(url), acmeAdmin, acme, null, null).getResponse().getContentAsString()).doesNotContain("Report Perfume Zq");
    }

    // ------------------------------------------------------------------------------------------ authorization + tenancy

    @Test
    void onlyAnAdminOfTheTenantMayCancelOrRefund() throws Exception {
        long p = product(acme, "Authz Perfume", "50.00", 10);
        String number = sell(p, 2);
        String before = snapshot(p);

        for (String action : new String[]{"cancel", "refund"}) {
            String url = "/api/v1/admin/walk-in/orders/" + number + "/" + action;
            int anon = mvc.perform(post(url).header("X-Tenant-ID", "acme").header("Idempotency-Key", "authz-key-0001")
                    .contentType("application/json").content("{\"reason\":\"x\"}")).andReturn().getResponse().getStatus();
            assertThat(anon).as(action + " anonymous").isIn(401, 403);
            status(send(post(url), cookie("accessToken", customer(acme, "authz-c-" + action + "@acme.test").token()), acme, Map.of("reason", "x"), "authz-key-0001"), 403);
            // FRONT_DESK is refused even with every front-desk permission granted: there is no permission for this
            status(send(post(url), frontDesk("fd-all-" + action + "@acme.test", true), acme, Map.of("reason", "x"), "authz-key-0001"), 403);
            status(send(post(url), frontDesk("fd-none-" + action + "@acme.test", false), acme, Map.of("reason", "x"), "authz-key-0001"), 403);
            // another tenant's admin is anonymous here (AUTH-01) and, on its own tenant, the order does not exist
            int foreign = mvc.perform(post(url).header("X-Tenant-ID", "acme").cookie(bravoAdmin).header("Idempotency-Key", "authz-key-0001")
                    .contentType("application/json").content("{\"reason\":\"x\"}")).andReturn().getResponse().getStatus();
            assertThat(foreign).as(action + " foreign admin cookie on acme").isIn(401, 403);
        }
        assertThat(snapshot(p)).isEqualTo(before);
        assertThat(orderStatusOf(number)).isEqualTo("COMPLETED");
        // the front desk can still sell and read, only the reversal is closed to it
        status(send(get("/api/v1/admin/walk-in/orders/" + number), frontDesk("fd-read@acme.test", true), acme, null, null), 200);
    }

    @Test
    void anotherTenantCannotSeeOrReverseThisTenantsOrder() throws Exception {
        long p = product(acme, "Tenant Perfume", "50.00", 10);
        String number = sell(p, 2);
        String before = snapshot(p);
        long bp = product(bravo, "Tenant Perfume B", "50.00", 10);
        MvcResult bravoSale = send(post(ORDER), bravoAdmin, bravo, Map.of("items", List.of(Map.of("productId", bp, "quantity", 1)),
                "paymentMethod", "CASH", "amountPaid", "500.00"), null);
        status(bravoSale, 200);
        String bravoNumber = body(bravoSale).at("/data/orderNumber").asText();

        status(cancel(bravoAdmin, bravo, number, "tenant-key-0001", REASON), 404);
        status(refund(bravoAdmin, bravo, number, "tenant-key-0001", REASON), 404);
        assertThat(snapshot(p)).isEqualTo(before);
        assertThat(orderStatusOf(number)).isEqualTo("COMPLETED");
        // and acme's admin cannot reach bravo's order either; bravo's own reversal works and acme stays untouched
        status(cancel(bravoNumber, "tenant-key-0002"), 404);
        status(cancel(bravoAdmin, bravo, bravoNumber, "tenant-key-0003", REASON), 200);
        assertThat(jdbc.queryForObject("SELECT status FROM tenant_bravo.walk_in_orders WHERE order_number = ?", String.class, bravoNumber)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM tenant_bravo.products WHERE id = ?", Integer.class, bp)).isEqualTo(10);
        assertThat(snapshot(p)).isEqualTo(before);
        // the same key in two tenants is two requests
        long q = product(acme, "Tenant Perfume Q", "50.00", 10);
        String second = sell(q, 1);
        status(cancel(second, "tenant-key-0003"), 200);
    }

    // ------------------------------------------------------------------------------------------ concurrency

    private List<MvcResult> race(List<Callable<MvcResult>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (Callable<MvcResult> c : calls) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return c.call();
                }));
            }
            go.countDown();
            List<MvcResult> out = new ArrayList<>();
            for (Future<MvcResult> f : futures) out.add(f.get(90, TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void manyCancelsWithDifferentKeysRestoreOnceAndExactlyOneWins() throws Exception {
        long p = product(acme, "Race Cancel", "50.00", 50);
        String number = sell(p, 3);
        String balancesBefore = balances();
        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String key = "race-cancel-" + String.format("%04d", i);
            calls.add(() -> cancel(number, key));
        }
        int ok = 0;
        int conflict = 0;
        for (MvcResult r : race(calls)) {
            int s = r.getResponse().getStatus();
            if (s == 200) ok++; else if (s == 409) { conflict++; assertThat(code(r, body(r))).isEqualTo("WALK_IN_ORDER_NOT_CANCELLABLE"); }
            else throw new AssertionError("unexpected " + s + " " + r.getResponse().getContentAsString());
        }
        assertThat(ok).isEqualTo(1);
        assertThat(conflict).isEqualTo(7);
        assertThat(stock(p)).isEqualTo(50);
        assertThat(layerRemaining(acme, p)).isEqualTo(50);
        assertThat(sold(p)).isZero();
        assertThat(movements(number, "RETURN")).isEqualTo(3);
        assertThat(entries(number, "WALK_IN_REVERSAL")).isEqualTo(1);
        assertThat(auditRows(number)).isEqualTo(1);
        assertThat(orderStatusOf(number)).isEqualTo("CANCELLED");
    }

    @Test
    void cancelAndRefundRacingOnOneSaleHaveOneWinnerAndOneEffect() throws Exception {
        for (int round = 0; round < 3; round++) {
            long p = product(acme, "Race Mixed " + round, "50.00", 30);
            String number = sell(p, 2);
            List<Callable<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                String c = "race-mixed-c-" + round + "-" + i + "-aaaa";
                String r = "race-mixed-r-" + round + "-" + i + "-bbbb";
                calls.add(() -> cancel(number, c));
                calls.add(() -> refund(number, r));
            }
            int ok = 0;
            for (MvcResult res : race(calls)) {
                int s = res.getResponse().getStatus();
                if (s == 200) ok++; else assertThat(s).as(res.getResponse().getContentAsString()).isEqualTo(409);
            }
            assertThat(ok).as("round " + round).isEqualTo(1);
            assertThat(orderStatusOf(number)).isIn("CANCELLED", "REFUNDED");
            assertThat(stock(p)).isEqualTo(30);
            assertThat(layerRemaining(acme, p)).isEqualTo(30);
            assertThat(sold(p)).isZero();
            assertThat(movements(number, "RETURN")).isEqualTo(2);
            assertThat(entries(number, "WALK_IN_REVERSAL")).isEqualTo(1);
            assertThat(auditRows(number)).isEqualTo(1);
        }
    }

    @Test
    void concurrentRetriesWithOneKeyMakeOneReversalAndTheRestReplay() throws Exception {
        long p = product(acme, "Race Same Key", "50.00", 50);
        String number = sell(p, 3);
        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) calls.add(() -> cancel(number, "race-samekey-0001"));
        int fresh = 0;
        int replays = 0;
        for (MvcResult r : race(calls)) {
            status(r, 200);
            if (body(r).at("/data/idempotentReplay").asBoolean()) replays++; else fresh++;
        }
        assertThat(fresh).isEqualTo(1);
        assertThat(replays).isEqualTo(7);
        assertThat(stock(p)).isEqualTo(50);
        assertThat(movements(number, "RETURN")).isEqualTo(3);
        assertThat(entries(number, "WALK_IN_REVERSAL")).isEqualTo(1);
        assertThat(auditRows(number)).isEqualTo(1);
    }

    @Test
    void aReversalAndANewSaleOfTheSameProductDoNotDeadlockOrLoseStock() throws Exception {
        long p = product(acme, "Race Sale", "50.00", 40);
        String number = sell(p, 5);
        List<Callable<MvcResult>> calls = new ArrayList<>();
        calls.add(() -> cancel(number, "race-sale-cancel-01"));
        for (int i = 0; i < 4; i++) calls.add(() -> send(post(ORDER), acmeAdmin, acme, sale(p, 1), null));
        for (MvcResult r : race(calls)) status(r, 200);
        assertThat(stock(p)).isEqualTo(36);   // 40 - 4 new sales, the first sale's 5 are back
        assertThat(layerRemaining(acme, p)).isEqualTo(36);
        assertThat(sold(p)).isEqualTo(4);
    }

    // ---- reviewer gaps (mutants R4, R6, R8a, R13, R20)

    @Test
    void notEnoughMobileMoneyToPayBackIs409AndNothingChanges() throws Exception {
        long p = product(acme, "Momo Perfume", "50.00", 10);
        Map<String, Object> req = sale(p, 1);
        req.put("paymentMethod", "MOBILE_MONEY");
        req.put("amountPaid", "55.00");
        MvcResult placed = send(post(ORDER), acmeAdmin, acme, req, null);
        status(placed, 200);
        String number = body(placed).at("/data/orderNumber").asText();
        jdbc.update("UPDATE tenant_acme.ledger_accounts SET balance = 0 WHERE category = 'MOBILE_MONEY'");
        String before = snapshot(p);
        MvcResult r = cancel(number, "momo-key-0001");
        status(r, 409);
        assertThat(code(r, body(r))).isEqualTo("INSUFFICIENT_CASH");
        assertThat(snapshot(p)).isEqualTo(before);
        assertThat(orderStatusOf(number)).isEqualTo("COMPLETED");
    }

    @Test
    void theStoredReasonIsTheStrippedOne() throws Exception {
        long p = product(acme, "Strip Perfume", "50.00", 10);
        String number = sell(p, 1);
        status(cancel(acmeAdmin, acme, number, "strip-key-0001", "   padded reason \t "), 200);
        assertThat(jdbc.queryForObject("SELECT a.reason FROM tenant_acme.walk_in_order_audit_events a JOIN tenant_acme.walk_in_orders o ON o.id = a.walk_in_order_id "
                + "WHERE o.order_number = ?", String.class, number)).isEqualTo("padded reason");
    }

    @Test
    void theReversalIsDatedNowNotAtTheSaleDate() throws Exception {
        long p = product(acme, "Dated Perfume", "50.00", 10);
        String number = sell(p, 1);
        jdbc.update("UPDATE tenant_acme.journal_entries SET transaction_date = now() - interval '30 days' WHERE reference_id = ?", number);
        jdbc.update("UPDATE tenant_acme.walk_in_orders SET created_at = now() - interval '30 days' WHERE order_number = ?", number);
        status(cancel(number, "dated-key-0001"), 200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.journal_entries WHERE reference_id = ? AND type = 'WALK_IN_REVERSAL' "
                + "AND transaction_date > now() - interval '5 minutes'", Integer.class, number)).isEqualTo(1);
    }

    @Test
    void earlierLocationReturnsAreNettedAndOnlyTheRestGoesBack() throws Exception {
        long p = product(acme, "Netted Perfume", "50.00", 10);
        List<Long> priorSources = jdbc.queryForList("SELECT id FROM tenant_acme.storage_locations WHERE is_walk_in_sale_source", Long.class);
        jdbc.update("UPDATE tenant_acme.storage_locations SET is_walk_in_sale_source = false");
        Long location = jdbc.queryForObject("INSERT INTO tenant_acme.storage_locations (name, type, active, is_walk_in_sale_source, is_ecommerce_fulfilment_source, is_default_receiving, version, created_at) "
                + "VALUES ('WCR Net Floor', 'SHOP_FLOOR', true, true, false, false, 0, now()) RETURNING id", Long.class);
        try {
            String number = sell(p, 3);
            // one unit already went back to the location by an earlier partial return
            jdbc.update("INSERT INTO tenant_acme.stock_transfers (product_id, to_location_id, quantity, transfer_type, balance_after, reference_type, reference_id, created_at) "
                    + "VALUES (?, ?, 1, 'SALE_RETURN', -2, 'WALK_IN_ORDER', ?, now())", p, location, number);
            jdbc.update("UPDATE tenant_acme.location_stock SET quantity_on_hand = -2 WHERE product_id = ? AND location_id = ?", p, location);
            status(cancel(number, "netted-key-0001"), 200);
            assertThat(jdbc.queryForObject("SELECT quantity_on_hand FROM tenant_acme.location_stock WHERE product_id = ? AND location_id = ?", Integer.class, p, location))
                    .as("2 units come back, not 3").isZero();
        } finally {
            jdbc.update("DELETE FROM tenant_acme.stock_transfers WHERE from_location_id = ? OR to_location_id = ?", location, location);
            jdbc.update("DELETE FROM tenant_acme.location_stock WHERE location_id = ?", location);
            jdbc.update("DELETE FROM tenant_acme.storage_locations WHERE id = ?", location);
            for (Long id : priorSources) jdbc.update("UPDATE tenant_acme.storage_locations SET is_walk_in_sale_source = true WHERE id = ?", id);
        }
    }

    @Test
    void refundedAndCancelledWalkInsLeaveAllSixItemsSoldQueries() throws Exception {
        long live1 = product(acme, "Live One", "10.00", 10);
        long live2 = product(acme, "Live Two", "10.00", 10);
        long refunded = product(acme, "Refunded Item", "10.00", 10);
        long cancelled = product(acme, "Cancelled Item", "10.00", 10);
        var start = java.time.LocalDateTime.now().minusSeconds(1);
        sell(live1, 1);
        sell(live2, 1);
        String refNo = sell(refunded, 2);
        String canNo = sell(cancelled, 3);
        var refTypes = List.of(com.example.perfume_budget.enums.InventoryReferenceType.WALK_IN_ORDER);
        var end = java.time.LocalDateTime.now().plusDays(1);
        java.util.function.Supplier<String> probe = () -> tenants.runAs(acme, () -> {
            var page = movementRepository.findSoldProductAggregates(refTypes, start, end, org.springframework.data.domain.PageRequest.of(0, 1));
            var totals = movementRepository.sumSales(refTypes, start, end);
            return page.getTotalElements() + "|" + totals.getTotalQuantity() + "|"
                    + movementRepository.sumSalesForProduct(refunded, refTypes, start, end).getTotalQuantity() + "|"
                    + movementRepository.sumSalesForProduct(cancelled, refTypes, start, end).getTotalQuantity() + "|"
                    + movementRepository.sumSaleAmountsByReference(refunded, refTypes, start, end).size() + "|"
                    + movementRepository.sumSaleAmountsByReference(cancelled, refTypes, start, end).size() + "|"
                    + movementRepository.findSaleCurrencies(List.of(com.example.perfume_budget.enums.InventoryReferenceType.WALK_IN_ORDER), start, end).size();
        });
        assertThat(probe.get()).as("before: 4 products, 7 units, both reversible lines present").isEqualTo("4|7|2|3|1|1|1");
        status(refund(refNo, "six-key-refund-1"), 200);
        status(cancel(canNo, "six-key-cancel-1"), 200);
        assertThat(probe.get()).as("after: only the two live products, 2 units, no reference rows for the reversed sales").isEqualTo("2|2|0|0|0|0|1");
    }

    // ------------------------------------------------------------------------------------------ migration

    @Test
    void v19IsReRunnable() {
        jdbc.update("DELETE FROM tenant_acme.flyway_schema_history WHERE version IS NOT NULL AND CAST(version AS INTEGER) >= 19");
        migrator.migrateTenant("tenant_acme");
        assertThat(jdbc.queryForObject("SELECT success FROM tenant_acme.flyway_schema_history WHERE version = '19'", Boolean.class)).isTrue();
        migrator.migrateTenant("tenant_acme"); // no-op
    }
}
