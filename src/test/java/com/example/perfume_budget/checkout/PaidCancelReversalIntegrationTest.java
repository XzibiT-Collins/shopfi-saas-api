package com.example.perfume_budget.checkout;

import com.example.perfume_budget.dto.payment.response.PaystackVerifyTransactionResponse;
import com.example.perfume_budget.enums.InventoryReferenceType;
import com.example.perfume_budget.enums.PaymentStatus;
import com.example.perfume_budget.enums.SalesAnalyticsGranularity;
import com.example.perfume_budget.enums.SalesAnalyticsSource;
import com.example.perfume_budget.repository.InventoryMovementRepository;
import com.example.perfume_budget.repository.JournalEntryRepository;
import com.example.perfume_budget.repository.OrderItemRepository;
import com.example.perfume_budget.repository.OrderRepository;
import com.example.perfume_budget.service.interfaces.AdminMetricService;
import com.example.perfume_budget.support.TestTenants;
import com.example.perfume_budget.tenancy.TenantSchemaMigrator;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * ORD-02 against the real app + Postgres: a paid merchant cancel reverses the books, sales analytics and revenue
 * reports; the ledger return goes to the location of the deduction; return-received; the audit read endpoint;
 * append-only audit rows; and cancel vs webhook vs checkout concurrency.
 */
class PaidCancelReversalIntegrationTest extends AbstractCheckoutIntegrationTest {

    @Autowired OrderRepository orderRepository;
    @Autowired OrderItemRepository orderItemRepository;
    @Autowired InventoryMovementRepository movementRepository;
    @Autowired JournalEntryRepository journalRepository;
    @Autowired AdminMetricService metrics;
    @Autowired TenantSchemaMigrator migrator;

    private record Placed(Customer c, List<Long> products, String orderNumber, String reference) {}

    private String adminTokenCache;
    private String bravoAdminTokenCache;

    @BeforeEach
    void books() {
        // the test products are seeded by SQL (no purchase entry): give the critical ledger accounts room to move
        for (String s : new String[]{"tenant_acme", "tenant_bravo"}) {
            jdbc.update("UPDATE " + s + ".ledger_accounts SET balance = balance + 100000 WHERE category IN ('INVENTORY', 'CASH')");
        }
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder b) throws Exception {
        if (adminTokenCache == null) {
            adminTokenCache = setCookieValue(loginPassword("acme", acme.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken");
        }
        return b.header("X-Tenant-ID", "acme").cookie(cookie("accessToken", adminTokenCache));
    }

    private MockHttpServletRequestBuilder bravoAdminOnAcme(MockHttpServletRequestBuilder b) throws Exception {
        if (bravoAdminTokenCache == null) {
            bravoAdminTokenCache = setCookieValue(loginPassword("bravo", bravo.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken");
        }
        return b.header("X-Tenant-ID", "acme").cookie(cookie("accessToken", bravoAdminTokenCache));
    }

    private long costedProduct(String name, int stock) {
        long p = product(acme, name, "10.00", stock);
        jdbc.update("UPDATE tenant_acme.products SET cost_price_amount = 4.00, cost_price_currency = 'GHS' WHERE id = ?", p);
        return p;
    }

    private Placed place(String email, int qty, long... products) throws Exception {
        List<Long> ps = new ArrayList<>();
        if (products.length == 0) ps.add(costedProduct("Rev " + email, 10));
        else for (long p : products) ps.add(p);
        Customer c = customer(acme, email);
        for (long p : ps) cart(c, p, qty);
        MvcResult r = checkout(c, null, null);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode d = body(r).get("data");
        return new Placed(c, ps, d.get("orderNumber").asText(), d.at("/data/reference").asText());
    }

    private long minor(String orderNumber) {
        return jdbc.queryForObject("SELECT total_amount * 100 FROM tenant_acme.orders WHERE order_number = ?", BigDecimal.class, orderNumber).longValue();
    }

    private BigDecimal total(String orderNumber) {
        return jdbc.queryForObject("SELECT total_amount FROM tenant_acme.orders WHERE order_number = ?", BigDecimal.class, orderNumber);
    }

    private Placed placePaid(String email, int qty, long... products) throws Exception {
        Placed o = place(email, qty, products);
        verifyAnswer = new PaystackVerifyTransactionResponse(true, "ok",
                new PaystackVerifyTransactionResponse.Data("success", o.reference(), BigDecimal.valueOf(minor(o.orderNumber())), "GHS", "card", "Successful"));
        verify(o.c(), o.reference());
        verifyAnswer = null;
        assertThat(orderStatus(acme, o.orderNumber())).isEqualTo("COMPLETED");
        return o;
    }

    private MvcResult setStatus(String orderNumber, String status) throws Exception {
        return mvc.perform(admin(put("/api/v1/order/update-order-status/" + orderNumber)
                .contentType("application/json").content("{\"orderStatus\":\"" + status + "\"}"))).andReturn();
    }

    private MvcResult returnReceived(String orderNumber) throws Exception {
        return mvc.perform(admin(post("/api/v1/order/" + orderNumber + "/return-received"))).andReturn();
    }

    private MvcResult ack(String orderNumber) throws Exception {
        return mvc.perform(admin(post("/api/v1/order/" + orderNumber + "/refund-acknowledgement"))).andReturn();
    }

    private MvcResult auditEvents(String orderNumber, String query) throws Exception {
        return mvc.perform(admin(get("/api/v1/order/" + orderNumber + "/audit-events" + (query == null ? "" : "?" + query)))).andReturn();
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private String code(MvcResult r) throws Exception {
        return body(r).at("/data/code").asText();
    }

    /** Credit-positive net of the order's journal lines on one account category (all entry types). */
    private BigDecimal net(String orderNumber, String category) {
        return jdbc.queryForObject("SELECT coalesce(sum(CASE WHEN l.entry_type = 'CREDIT' THEN l.amount ELSE -l.amount END), 0) "
                + "FROM tenant_acme.journal_entry_lines l JOIN tenant_acme.journal_entries j ON j.id = l.journal_entry_id "
                + "JOIN tenant_acme.ledger_accounts a ON a.id = l.account_id WHERE j.reference_id = ? AND a.category = ?",
                BigDecimal.class, orderNumber, category);
    }

    private int entries(String orderNumber, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM tenant_acme.journal_entries WHERE reference_id = ? AND type = ?",
                Integer.class, orderNumber, type);
    }

    private BigDecimal accountBalance(String category) {
        return jdbc.queryForObject("SELECT balance FROM tenant_acme.ledger_accounts WHERE category = ?", BigDecimal.class, category);
    }

    private int movements(String type, String orderNumber) {
        return jdbc.queryForObject("SELECT count(*) FROM tenant_acme.inventory_movements WHERE movement_type = ? AND reference_id = ?",
                Integer.class, type, orderNumber);
    }

    private int ledger(String orderNumber, String transferType) {
        return jdbc.queryForObject("SELECT coalesce(sum(quantity),0) FROM tenant_acme.stock_transfers WHERE reference_id = ? AND transfer_type = ?",
                Integer.class, orderNumber, transferType);
    }

    private int onHand(long productId, long locationId) {
        return jdbc.queryForObject("SELECT coalesce(sum(quantity_on_hand),0) FROM tenant_acme.location_stock WHERE product_id = ? AND location_id = ?",
                Integer.class, productId, locationId);
    }

    private long fulfilmentSource() {
        return jdbc.queryForObject("SELECT id FROM tenant_acme.storage_locations WHERE is_ecommerce_fulfilment_source", Long.class);
    }

    private String analyticsRevenueCard() {
        LocalDate today = LocalDate.now();
        return tenants.runAs(acme, () -> metrics.getSalesAnalytics(SalesAnalyticsSource.ONLINE, SalesAnalyticsGranularity.DAY, today, today)
                .miniStats().stream().filter(m -> m.label().equals("Total Revenue")).findFirst().orElseThrow().value());
    }

    private record Reports(BigDecimal totalRevenue, long completedOrders, int topProducts, long itemsSold, BigDecimal analyticsRevenue) {}

    private Reports reports() {
        LocalDateTime start = LocalDate.now().minusDays(1).atStartOfDay();
        LocalDateTime end = LocalDate.now().plusDays(1).atStartOfDay();
        return tenants.runAs(acme, () -> new Reports(
                orderRepository.getTotalRevenue(PaymentStatus.COMPLETED),
                orderRepository.countCompletedOnlineOrders(start, end),
                orderItemRepository.findTopProductsByRevenue(PaymentStatus.COMPLETED).size(),
                movementRepository.sumSales(List.of(InventoryReferenceType.ORDER), start, end).getTotalQuantity(),
                journalRepository.getRevenueAndCOGSByBucket(List.of("SALE", "SALE_REVERSAL"), start, end, "day").stream()
                        .map(b -> b.getRevenue()).reduce(BigDecimal.ZERO, BigDecimal::add)));
    }

    private <T> List<T> concurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> fs = new ArrayList<>();
            for (Callable<T> t : tasks) fs.add(pool.submit(() -> { go.await(); return t.call(); }));
            go.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : fs) out.add(f.get());
            return out;
        } finally {
            pool.shutdown();
        }
    }

    // ------------------------------------------------------------------------------------------ item 1: reversal

    @Test
    void paidCancelBeforeDispatchReversesBooksAnalyticsAndRevenueReportsOnce() throws Exception {
        String emptyCard = analyticsRevenueCard();
        Placed o = placePaid("rv1@acme.test", 3);
        BigDecimal total = total(o.orderNumber());
        assertThat(net(o.orderNumber(), "SALES_REVENUE")).isEqualByComparingTo("30.00");
        assertThat(net(o.orderNumber(), "COGS")).isEqualByComparingTo("-12.00");
        Reports sold = reports();
        assertThat(sold.totalRevenue()).isEqualByComparingTo(total);
        assertThat(sold.completedOrders()).isEqualTo(1);
        assertThat(sold.topProducts()).isEqualTo(1);
        assertThat(sold.itemsSold()).isEqualTo(3);
        assertThat(sold.analyticsRevenue()).isEqualByComparingTo("30.00");
        assertThat(analyticsRevenueCard()).isNotEqualTo(emptyCard);
        BigDecimal cashBefore = accountBalance("CASH");
        BigDecimal refundsBefore = accountBalance("REFUND_PAYABLE");

        MvcResult r = setStatus(o.orderNumber(), "CANCELLED");
        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r).at("/data/paymentStatus").asText()).as("payment stays COMPLETED").isEqualTo("COMPLETED");
        assertThat(body(r).at("/data/refundRequired").asBoolean()).isTrue();

        assertThat(entries(o.orderNumber(), "SALE_REVERSAL")).isEqualTo(1);
        assertThat(net(o.orderNumber(), "SALES_REVENUE")).isEqualByComparingTo("0");
        assertThat(net(o.orderNumber(), "TAX_PAYABLE")).isEqualByComparingTo("0");
        assertThat(net(o.orderNumber(), "DISCOUNT_EXPENSE")).isEqualByComparingTo("0");
        assertThat(net(o.orderNumber(), "COGS")).as("goods are back on the shelf").isEqualByComparingTo("0");
        assertThat(net(o.orderNumber(), "INVENTORY")).isEqualByComparingTo("0");
        assertThat(net(o.orderNumber(), "REFUND_PAYABLE")).isEqualByComparingTo(total);
        assertThat(accountBalance("CASH")).as("cash leaves only when refunded").isEqualByComparingTo(cashBefore);
        assertThat(accountBalance("REFUND_PAYABLE")).isEqualByComparingTo(refundsBefore.add(total));

        Reports after = reports();
        assertThat(after.totalRevenue()).isEqualByComparingTo("0");
        assertThat(after.completedOrders()).isZero();
        assertThat(after.topProducts()).isZero();
        assertThat(after.itemsSold()).isZero();
        assertThat(after.analyticsRevenue()).isEqualByComparingTo("0");
        assertThat(analyticsRevenueCard()).isEqualTo(emptyCard);

        // double cancel: a no-op, nothing reversed twice
        assertThat(status(setStatus(o.orderNumber(), "CANCELLED"))).isEqualTo(200);
        assertThat(entries(o.orderNumber(), "SALE_REVERSAL")).isEqualTo(1);
        assertThat(net(o.orderNumber(), "SALES_REVENUE")).isEqualByComparingTo("0");
        assertThat(movements("RETURN", o.orderNumber())).isEqualTo(1);
    }

    @Test
    void refundAcknowledgementPaysOutTheRefundPayableOnce() throws Exception {
        Placed o = placePaid("rv2@acme.test", 2);
        BigDecimal total = total(o.orderNumber());
        setStatus(o.orderNumber(), "CANCELLED");
        BigDecimal cashBefore = accountBalance("CASH");
        assertThat(status(ack(o.orderNumber()))).isEqualTo(200);
        assertThat(entries(o.orderNumber(), "REFUND")).isEqualTo(1);
        assertThat(net(o.orderNumber(), "REFUND_PAYABLE")).isEqualByComparingTo("0");
        assertThat(net(o.orderNumber(), "CASH")).as("sale debit and refund credit cancel out").isEqualByComparingTo("0");
        assertThat(accountBalance("CASH")).isEqualByComparingTo(cashBefore.subtract(total));
        assertThat(code(ack(o.orderNumber()))).isEqualTo("REFUND_NOT_REQUIRED");
        assertThat(entries(o.orderNumber(), "REFUND")).isEqualTo(1);
    }

    @Test
    void unpaidCancelAndLatePaymentRefundsNeverTouchTheBooks() throws Exception {
        Gateway g = configureGateway(acme);
        Placed o = place("rv3@acme.test", 1);
        assertThat(status(setStatus(o.orderNumber(), "CANCELLED"))).isEqualTo(200);
        assertThat(webhook(g, event("charge.success", o.reference(), minor(o.orderNumber())))).isEqualTo(200);
        await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() -> assertThat(
                jdbc.queryForObject("SELECT p.refund_required FROM tenant_acme.payments p JOIN tenant_acme.orders o ON o.id = p.order_id WHERE o.order_number = ?",
                        Boolean.class, o.orderNumber())).isTrue());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.journal_entries WHERE reference_id = ?", Integer.class, o.orderNumber())).isZero();
        assertThat(status(ack(o.orderNumber()))).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.journal_entries WHERE reference_id = ?", Integer.class, o.orderNumber()))
                .as("a payment that was never booked as a sale is not booked as a refund either").isZero();
    }

    // -------------------------------------------------------------------------- item 7: OUT_FOR_DELIVERY + return

    @Test
    void outForDeliveryCancelKeepsGoodsConsumedUntilTheReturnIsReceived() throws Exception {
        Placed o = placePaid("rv4@acme.test", 2);
        long p = o.products().get(0);
        assertThat(status(setStatus(o.orderNumber(), "OUT_FOR_DELIVERY"))).isEqualTo(200);
        assertThat(status(setStatus(o.orderNumber(), "CANCELLED"))).isEqualTo(200);
        assertThat(layerRemaining(acme, p)).as("goods are with the courier").isEqualTo(8);
        assertThat(net(o.orderNumber(), "SALES_REVENUE")).isEqualByComparingTo("0");
        assertThat(net(o.orderNumber(), "COGS")).as("cost stays booked while the goods are out").isEqualByComparingTo("-8.00");
        assertThat(reports().itemsSold()).as("not a sale any more").isZero();

        MvcResult r = returnReceived(o.orderNumber());
        assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(r.getResponse().getHeader("ETag")).isNotNull();
        assertThat(layerRemaining(acme, p)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT sold_count FROM tenant_acme.products WHERE id = ?", Integer.class, p)).isZero();
        assertThat(net(o.orderNumber(), "COGS")).isEqualByComparingTo("0");
        assertThat(net(o.orderNumber(), "INVENTORY")).isEqualByComparingTo("0");
        assertThat(entries(o.orderNumber(), "SALE_REVERSAL")).isEqualTo(2);
        assertThat(ledger(o.orderNumber(), "SALE_RETURN")).isEqualTo(2);

        MvcResult again = returnReceived(o.orderNumber());
        assertThat(status(again)).isEqualTo(409);
        assertThat(code(again)).isEqualTo("NOTHING_TO_RESTOCK");
        assertThat(layerRemaining(acme, p)).isEqualTo(10);
        assertThat(entries(o.orderNumber(), "SALE_REVERSAL")).isEqualTo(2);

        Placed live = placePaid("rv4b@acme.test", 1);
        assertThat(code(returnReceived(live.orderNumber()))).isEqualTo("RETURN_NOT_APPLICABLE");
        Placed unpaid = place("rv4c@acme.test", 1);
        setStatus(unpaid.orderNumber(), "CANCELLED");
        assertThat(code(returnReceived(unpaid.orderNumber()))).isEqualTo("RETURN_NOT_APPLICABLE");
        assertThat(status(returnReceived("PB-20990101-NOPE"))).isEqualTo(404);
    }

    @Test
    void simultaneousReturnReceivedRestocksOnce() throws Exception {
        Placed o = placePaid("rv5@acme.test", 3);
        setStatus(o.orderNumber(), "OUT_FOR_DELIVERY");
        setStatus(o.orderNumber(), "CANCELLED");
        List<Integer> codes = concurrently(List.of(() -> status(returnReceived(o.orderNumber())),
                () -> status(returnReceived(o.orderNumber())), () -> status(returnReceived(o.orderNumber()))));
        assertThat(codes).containsOnlyOnce(200).containsOnly(200, 409);
        assertThat(layerRemaining(acme, o.products().get(0))).isEqualTo(10);
        assertThat(movements("RETURN", o.orderNumber())).isEqualTo(1);
        assertThat(entries(o.orderNumber(), "SALE_REVERSAL")).isEqualTo(2);
    }

    // ---------------------------------------------------------------------- item 2: location of the deduction

    @Test
    void ledgerReturnGoesToTheDeductionLocationEvenAfterTheFulfilmentSourceMoved() throws Exception {
        long original = fulfilmentSource();
        Placed o = placePaid("rv6@acme.test", 3);
        long p = o.products().get(0);
        assertThat(jdbc.queryForObject("SELECT DISTINCT fulfilment_location_id FROM tenant_acme.inventory_allocations WHERE reference_id = ?",
                Long.class, o.orderNumber())).isEqualTo(original);
        long other = jdbc.queryForObject("INSERT INTO tenant_acme.storage_locations (name, type, active, is_default_receiving, is_walk_in_sale_source, "
                + "is_ecommerce_fulfilment_source, version, created_at) VALUES ('Dispatch ' || gen_random_uuid(), 'STORE_ROOM', true, false, false, false, 0, now()) RETURNING id", Long.class);
        try {
            moveFulfilmentSource(other);
            assertThat(onHand(p, original)).isEqualTo(-3);
            assertThat(status(setStatus(o.orderNumber(), "CANCELLED"))).isEqualTo(200);
            assertThat(onHand(p, original)).as("returned where it was deducted").isZero();
            assertThat(onHand(p, other)).isZero();
            assertThat(jdbc.queryForObject("SELECT to_location_id FROM tenant_acme.stock_transfers WHERE reference_id = ? AND transfer_type = 'SALE_RETURN'",
                    Long.class, o.orderNumber())).isEqualTo(original);
        } finally {
            moveFulfilmentSource(original);
        }
    }

    private void moveFulfilmentSource(long to) {
        jdbc.update("UPDATE tenant_acme.storage_locations SET is_ecommerce_fulfilment_source = false WHERE is_ecommerce_fulfilment_source");
        jdbc.update("UPDATE tenant_acme.storage_locations SET is_ecommerce_fulfilment_source = true WHERE id = ?", to);
    }

    @Test
    void legacyAllocationWithoutARecordedLocationFallsBackToTheCurrentSource() throws Exception {
        long source = fulfilmentSource();
        Placed o = placePaid("rv7@acme.test", 2);
        long p = o.products().get(0);
        // simulate a pre-ORD-02 row the V14 backfill could not match
        jdbc.update("UPDATE tenant_acme.inventory_allocations SET fulfilment_location_id = NULL, fulfilment_location_recorded = false WHERE reference_id = ?",
                o.orderNumber());
        setStatus(o.orderNumber(), "CANCELLED");
        assertThat(ledger(o.orderNumber(), "SALE_RETURN")).isEqualTo(2);
        assertThat(onHand(p, source)).isZero();
    }

    @Test
    void recordedButUndeductedAllocationIsNotReturnedToTheLedger() throws Exception {
        Placed o = placePaid("rv8@acme.test", 2);
        jdbc.update("UPDATE tenant_acme.inventory_allocations SET fulfilment_location_id = NULL, fulfilment_location_recorded = true WHERE reference_id = ?",
                o.orderNumber());
        setStatus(o.orderNumber(), "CANCELLED");
        assertThat(ledger(o.orderNumber(), "SALE_RETURN")).isZero();
        assertThat(layerRemaining(acme, o.products().get(0))).as("the stock itself is still returned").isEqualTo(10);
    }

    @Test
    void v14BackfillMatchesTheNewestDeductionOfTheOrderAndProduct() throws Exception {
        long source = fulfilmentSource();
        Placed o = placePaid("rv9@acme.test", 2);
        jdbc.update("UPDATE tenant_acme.inventory_allocations SET fulfilment_location_id = NULL, fulfilment_location_recorded = false WHERE reference_id = ?",
                o.orderNumber());
        // the V14 backfill statement, re-run on this tenant
        String sql = new String(getClass().getResourceAsStream("/db/migrations/tenant/V14__paid_cancel_reversal.sql").readAllBytes());
        String update = sql.substring(sql.indexOf("UPDATE inventory_allocations"), sql.indexOf(";", sql.indexOf("UPDATE inventory_allocations")));
        jdbc.execute(update.replace("UPDATE inventory_allocations", "UPDATE tenant_acme.inventory_allocations")
                .replace("FROM stock_transfers", "FROM tenant_acme.stock_transfers"));
        Map<String, Object> row = jdbc.queryForMap("SELECT fulfilment_location_id AS loc, fulfilment_location_recorded AS rec FROM tenant_acme.inventory_allocations WHERE reference_id = ?",
                o.orderNumber());
        assertThat(((Number) row.get("loc")).longValue()).isEqualTo(source);
        assertThat(row.get("rec")).isEqualTo(true);
    }

    // ---------------------------------------------------------------------- items 3 + 6: audit trail

    @Test
    void auditReadEndpointListsTheTrailOldestFirstPagedAndX20RefundState() throws Exception {
        Placed o = placePaid("au1@acme.test", 1);
        setStatus(o.orderNumber(), "PACKING");
        setStatus(o.orderNumber(), "OUT_FOR_DELIVERY");
        setStatus(o.orderNumber(), "CANCELLED");
        returnReceived(o.orderNumber());
        ack(o.orderNumber());

        MvcResult r = auditEvents(o.orderNumber().toLowerCase(), null);
        assertThat(status(r)).isEqualTo(200);
        JsonNode page = body(r).get("data");
        assertThat(page.get("totalElements").asInt()).isEqualTo(5);
        List<String> types = new ArrayList<>();
        page.get("content").forEach(n -> types.add(n.get("eventType").asText()));
        assertThat(types).containsExactly("STATUS_CHANGED", "STATUS_CHANGED", "CANCELLED", "RETURN_RECEIVED", "REFUND_ACKNOWLEDGED");
        JsonNode cancel = page.get("content").get(2);
        assertThat(cancel.get("fromStatus").asText()).isEqualTo("OUT_FOR_DELIVERY");
        assertThat(cancel.get("refundRequired").asBoolean()).isTrue();
        assertThat(cancel.get("actorEmail").asText()).isEqualTo(acme.adminEmail());
        assertThat(cancel.get("createdAt").asText()).endsWith("Z");
        assertThat(page.get("content").get(0).get("refundRequired").asBoolean()).isFalse();

        JsonNode second = body(auditEvents(o.orderNumber(), "page=1&size=2&sort=id,desc")).get("data");
        assertThat(second.get("size").asInt()).isEqualTo(2);
        assertThat(second.get("content").get(0).get("eventType").asText()).as("client sort ignored").isEqualTo("CANCELLED");
        assertThat(body(auditEvents(o.orderNumber(), "size=100000")).at("/data/size").asInt()).isEqualTo(100);
        assertThat(code(auditEvents(o.orderNumber(), "page=1000001"))).isEqualTo("INVALID_PAGE");
        MvcResult unknown = auditEvents("PB-20990101-NOPE", null);
        assertThat(status(unknown)).isEqualTo(404);
        assertThat(code(unknown)).isEqualTo("ORDER_NOT_FOUND");
    }

    @Test
    void x20_cancelOfAFailedOrderThatStillOwesARefundIsAuditedAsRefundRequired() throws Exception {
        Placed o = place("au2@acme.test", 1);
        // PAY-01 late payment on a FAILED order without stock: payment COMPLETED + refund flagged, order still FAILED
        jdbc.update("UPDATE tenant_acme.orders SET status = 'FAILED' WHERE order_number = ?", o.orderNumber());
        jdbc.update("UPDATE tenant_acme.payments SET status = 'COMPLETED', refund_required = true WHERE order_id = (SELECT id FROM tenant_acme.orders WHERE order_number = ?)", o.orderNumber());
        MvcResult r = setStatus(o.orderNumber(), "CANCELLED");
        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r).at("/data/refundRequired").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT e.refund_required FROM tenant_acme.order_audit_events e JOIN tenant_acme.orders o ON o.id = e.order_id "
                + "WHERE o.order_number = ? AND e.event_type = 'CANCELLED'", Boolean.class, o.orderNumber())).isTrue();

        Placed plain = place("au2b@acme.test", 1);
        jdbc.update("UPDATE tenant_acme.orders SET status = 'FAILED' WHERE order_number = ?", plain.orderNumber());
        jdbc.update("UPDATE tenant_acme.payments SET status = 'FAILED' WHERE order_id = (SELECT id FROM tenant_acme.orders WHERE order_number = ?)", plain.orderNumber());
        setStatus(plain.orderNumber(), "CANCELLED");
        assertThat(jdbc.queryForObject("SELECT e.refund_required FROM tenant_acme.order_audit_events e JOIN tenant_acme.orders o ON o.id = e.order_id "
                + "WHERE o.order_number = ? AND e.event_type = 'CANCELLED'", Boolean.class, plain.orderNumber())).isFalse();
    }

    @Test
    void newRoutesAreAdminOnlyAndTenantScoped() throws Exception {
        Placed o = place("au3@acme.test", 1);
        String[][] routes = {{"POST", "/api/v1/order/" + o.orderNumber() + "/return-received"},
                {"GET", "/api/v1/order/" + o.orderNumber() + "/audit-events"}};
        for (String[] rt : routes) {
            MockHttpServletRequestBuilder anon = (rt[0].equals("POST") ? post(rt[1]) : get(rt[1])).header("X-Tenant-ID", "acme");
            assertThat(status(mvc.perform(anon).andReturn())).as("anonymous " + rt[1]).isEqualTo(401);
            assertThat(status(mvc.perform(as(rt[0].equals("POST") ? post(rt[1]) : get(rt[1]), o.c())).andReturn())).as("customer " + rt[1]).isEqualTo(403);
            assertThat(status(mvc.perform(bravoAdminOnAcme(rt[0].equals("POST") ? post(rt[1]) : get(rt[1]))).andReturn())).as("foreign admin " + rt[1]).isEqualTo(401);
        }
        // bravo's order is invisible to acme's admin
        long bp = product(bravo, "Bravo audit", "5.00", 5);
        Customer bc = customer(bravo, "au3@bravo.test");
        cart(bc, bp, 1);
        String bravoOrder = body(checkout(bc, null, null)).at("/data/orderNumber").asText();
        assertThat(status(auditEvents(bravoOrder, null))).isEqualTo(404);
        assertThat(status(returnReceived(bravoOrder))).isEqualTo(404);
    }

    @Test
    void auditRowsAreAppendOnlyAndTheirOrderCannotBeDeleted() throws Exception {
        Placed o = place("au4@acme.test", 1);
        setStatus(o.orderNumber(), "CANCELLED");
        long orderId = jdbc.queryForObject("SELECT id FROM tenant_acme.orders WHERE order_number = ?", Long.class, o.orderNumber());
        for (String sql : List.of("UPDATE tenant_acme.order_audit_events SET actor_email = 'x@y.z' WHERE order_id = " + orderId,
                "DELETE FROM tenant_acme.order_audit_events WHERE order_id = " + orderId)) {
            assertThatThrownBy(() -> jdbc.update(sql)).as(sql).hasMessageContaining("append-only");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.order_audit_events WHERE order_id = ?", Integer.class, orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT confdeltype FROM pg_constraint WHERE conname = 'fk_order_audit_events_order' "
                + "AND conrelid = 'tenant_acme.order_audit_events'::regclass", String.class)).isEqualTo("r");
    }

    @Test
    void aTenantSchemaWithAuditHistoryCanStillBeDropped() {
        String schema = "tenant_ord02drop";
        migrator.dropTenantSchema(schema);
        migrator.createTenantSchema(schema);
        migrator.migrateTenant(schema);
        try {
            jdbc.update("INSERT INTO " + schema + ".users (full_name, email, password, roles, is_active, email_verified, created_at) VALUES ('A', 'a@x.test', 'x', 'CUSTOMER', true, true, now())");
            long uid = jdbc.queryForObject("SELECT id FROM " + schema + ".users", Long.class);
            jdbc.update("INSERT INTO " + schema + ".orders (order_number, user_id, status, delivery_status, created_at, version, coupon_redemption_released) "
                    + "VALUES ('PB-DROP-1', ?, 'CANCELLED', 'CANCELLED', now(), 0, false)", uid);
            jdbc.update("INSERT INTO " + schema + ".order_audit_events (order_id, event_type, actor_email, created_at) "
                    + "SELECT id, 'CANCELLED', 'a@x.test', now() FROM " + schema + ".orders");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".order_audit_events", Integer.class)).isEqualTo(1);
            assertThatThrownBy(() -> jdbc.update("DELETE FROM " + schema + ".orders"))
                    .as("ON DELETE RESTRICT: the order's history cannot be cascaded away").hasMessageContaining("fk_order_audit_events_order");
            assertThatThrownBy(() -> jdbc.update("DELETE FROM " + schema + ".order_audit_events")).hasMessageContaining("append-only");
            // the test cleanup path (TRUNCATE ... CASCADE) still works
            jdbc.execute("TRUNCATE " + schema + ".orders CASCADE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".order_audit_events", Integer.class)).isZero();
        } finally {
            migrator.dropTenantSchema(schema);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.schemata WHERE schema_name = ?", Integer.class, schema)).isZero();
    }

    // ---------------------------------------------------------------------- concurrency: cancel vs webhook vs checkout

    @Test
    void paidCancelRacingAWebhookAndACheckoutOnTheSameProductsNeverDeadlocksAndBalances() throws Exception {
        Gateway g = configureGateway(acme);
        for (int round = 0; round < 3; round++) {
            long p1 = costedProduct("Race A " + round, 50);
            long p2 = costedProduct("Race B " + round, 50);
            Placed paid = placePaid("cc-paid-" + round + "@acme.test", 2, p2, p1);   // to be cancelled (restock both)
            Placed pending = place("cc-pend-" + round + "@acme.test", 3, p2, p1);    // its webhook settles it
            Customer shopper = customer(acme, "cc-shop-" + round + "@acme.test");
            cart(shopper, p2, 4);
            cart(shopper, p1, 4);
            long pendingMinor = minor(pending.orderNumber());

            List<Integer> codes = concurrently(List.of(
                    () -> status(setStatus(paid.orderNumber(), "CANCELLED")),
                    () -> { try { return webhook(g, event("charge.success", pending.reference(), pendingMinor)); } catch (Exception e) { throw new RuntimeException(e); } },
                    () -> status(checkout(shopper, null, null))));
            assertThat(codes).as("round " + round).containsOnly(200);
            await().atMost(java.time.Duration.ofSeconds(15)).until(() -> "COMPLETED".equals(orderStatus(acme, pending.orderNumber())));

            for (long p : new long[]{p1, p2}) {
                // 50 - pending 3 (sold) - shopper 4 (reserved); the cancelled order's 2 are back
                assertThat(layerRemaining(acme, p)).as("round " + round + " product " + p).isEqualTo(43);
                assertThat(jdbc.queryForObject("SELECT sold_count FROM tenant_acme.products WHERE id = ?", Integer.class, p)).isEqualTo(3);
            }
            assertThat(net(paid.orderNumber(), "SALES_REVENUE")).isEqualByComparingTo("0");
            assertThat(entries(paid.orderNumber(), "SALE_REVERSAL")).isEqualTo(1);
            assertThat(entries(pending.orderNumber(), "SALE")).isEqualTo(1);
        }
    }

    @Test
    void cancelRacingTheSuccessWebhookOfTheSameOrderLeavesTheBooksAtZeroWhicheverWins() throws Exception {
        Gateway g = configureGateway(acme);
        for (int i = 0; i < 4; i++) {
            Placed o = place("cw-" + i + "@acme.test", 2);
            long m = minor(o.orderNumber());
            concurrently(List.of(() -> status(setStatus(o.orderNumber(), "CANCELLED")),
                    () -> { try { return webhook(g, event("charge.success", o.reference(), m)); } catch (Exception e) { throw new RuntimeException(e); } }));
            await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() -> assertThat(
                    jdbc.queryForObject("SELECT p.status FROM tenant_acme.payments p JOIN tenant_acme.orders o ON o.id = p.order_id WHERE o.order_number = ?",
                            String.class, o.orderNumber())).isEqualTo("COMPLETED"));
            awaitBackgroundIdle();
            assertThat(net(o.orderNumber(), "SALES_REVENUE")).as("round " + i).isEqualByComparingTo("0");
            assertThat(net(o.orderNumber(), "COGS")).isEqualByComparingTo("0");
            assertThat(entries(o.orderNumber(), "SALE_REVERSAL")).isLessThanOrEqualTo(1);
            assertThat(entries(o.orderNumber(), "SALE")).isEqualTo(entries(o.orderNumber(), "SALE_REVERSAL"));
            assertThat(layerRemaining(acme, o.products().get(0))).isEqualTo(10);
        }
    }

    @Test
    void simultaneousPaidCancelsReverseOnce() throws Exception {
        Placed o = placePaid("dc@acme.test", 3);
        List<Integer> codes = concurrently(List.of(() -> status(setStatus(o.orderNumber(), "CANCELLED")),
                () -> status(setStatus(o.orderNumber(), "CANCELLED")), () -> status(setStatus(o.orderNumber(), "CANCELLED"))));
        assertThat(codes).containsOnly(200);
        assertThat(entries(o.orderNumber(), "SALE_REVERSAL")).isEqualTo(1);
        assertThat(movements("RETURN", o.orderNumber())).isEqualTo(1);
        assertThat(net(o.orderNumber(), "REFUND_PAYABLE")).isEqualByComparingTo(total(o.orderNumber()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.order_audit_events e JOIN tenant_acme.orders o ON o.id = e.order_id "
                + "WHERE o.order_number = ? AND e.event_type = 'CANCELLED'", Integer.class, o.orderNumber())).isEqualTo(1);
    }
}
