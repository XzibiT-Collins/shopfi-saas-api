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
 * ORD-07 against the real app + Postgres: the return of a whole DELIVERED order restocks to the deduction location,
 * reverses the books, sales analytics and revenue reports, gives the coupon redemption back, requires a manual refund
 * acknowledgement, is audited, idempotent and safe against concurrent admin actions.
 */
class DeliveredOrderReturnIntegrationTest extends AbstractCheckoutIntegrationTest {

    @Autowired OrderRepository orderRepository;
    @Autowired OrderItemRepository orderItemRepository;
    @Autowired InventoryMovementRepository movementRepository;
    @Autowired JournalEntryRepository journalRepository;
    @Autowired AdminMetricService metrics;
    @Autowired TenantSchemaMigrator migrator;
    @Autowired com.example.perfume_budget.service.OrderReturnPolicy returnPolicy;

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
        return placeAs(customer(acme, email), null, qty, products);
    }

    private Placed placeAs(Customer c, String query, int qty, long... products) throws Exception {
        List<Long> ps = new ArrayList<>();
        if (products.length == 0) ps.add(costedProduct("Rev " + c.email(), 10));
        else for (long p : products) ps.add(p);
        for (long p : ps) cart(c, p, qty);
        MvcResult r = checkout(c, null, query);
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
        return settle(place(email, qty, products));
    }

    private Placed settle(Placed o) throws Exception {
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

    /**
     * Every order-based report with a paid-cancel exclusion, and the netted analytics queries (ORD-02 rework F3): dashboard
     * total and daily revenue, completed count, top products, top customers, items sold (total and per order of each
     * product), coupon metrics (list and per coupon), analytics revenue / COGS / expenses (Query A + B).
     */
    private record Reports(BigDecimal totalRevenue, long completedOrders, int topProducts, long itemsSold, BigDecimal analyticsRevenue,
                           BigDecimal analyticsCogs, BigDecimal analyticsExpenses, BigDecimal dailyRevenue, Map<String, String> topCustomers,
                           long couponListUsages, BigDecimal couponListRevenue, long couponUsages, BigDecimal couponRevenue,
                           Map<String, BigDecimal> soldByReference) {}

    private static final List<String> EXPENSE_CATEGORIES = List.of("DISCOUNT_EXPENSE", "COUPON_EXPENSE", "MARKETING_EXPENSE",
            "LOGISTICS_EXPENSE", "MISCELLANEOUS_EXPENSE", "GENERAL_EXPENSE");

    private Reports reports() {
        LocalDateTime start = LocalDate.now().minusDays(1).atStartOfDay();
        LocalDateTime end = LocalDate.now().plusDays(1).atStartOfDay();
        List<Long> couponIds = jdbc.queryForList("SELECT id FROM tenant_acme.coupons", Long.class);
        List<Long> productIds = jdbc.queryForList("SELECT DISTINCT product_id FROM tenant_acme.inventory_movements WHERE movement_type = 'SALE'", Long.class);
        return tenants.runAs(acme, () -> {
            var buckets = journalRepository.getRevenueAndCOGSByBucket(List.of("SALE", "SALE_REVERSAL"), start, end, "day");
            var coupons = orderRepository.getAllCouponMetrics(PaymentStatus.COMPLETED);
            long couponUsages = 0;
            BigDecimal couponRevenue = BigDecimal.ZERO;
            for (Long id : couponIds) {
                var m = orderRepository.getCouponMetrics(id, PaymentStatus.COMPLETED);
                couponUsages += m.getTotalUsages();
                couponRevenue = couponRevenue.add(m.getRevenueGenerated());
            }
            Map<String, BigDecimal> byRef = new java.util.TreeMap<>();
            for (Long p : productIds) {
                movementRepository.sumSaleAmountsByReference(p, List.of(InventoryReferenceType.ORDER), start, end)
                        .forEach(a -> byRef.merge(a.getReferenceId(), a.getAmount().stripTrailingZeros(), BigDecimal::add));
            }
            return new Reports(
                    orderRepository.getTotalRevenue(PaymentStatus.COMPLETED),
                    orderRepository.countCompletedOnlineOrders(start, end),
                    orderItemRepository.findTopProductsByRevenue(PaymentStatus.COMPLETED).size(),
                    movementRepository.sumSales(List.of(InventoryReferenceType.ORDER), start, end).getTotalQuantity(),
                    buckets.stream().map(b -> b.getRevenue()).reduce(BigDecimal.ZERO, BigDecimal::add),
                    buckets.stream().map(b -> b.getCogs()).reduce(BigDecimal.ZERO, BigDecimal::add),
                    journalRepository.getExpensesByBucket(EXPENSE_CATEGORIES, start, end, "day").stream()
                            .map(b -> b.getExpenses()).reduce(BigDecimal.ZERO, BigDecimal::add),
                    orderRepository.getDailyRevenue(PaymentStatus.COMPLETED, start, end).stream()
                            .map(d -> d.getRevenue()).reduce(BigDecimal.ZERO, BigDecimal::add),
                    orderRepository.findTopCustomers(PaymentStatus.COMPLETED, org.springframework.data.domain.Pageable.ofSize(10)).stream()
                            .collect(java.util.stream.Collectors.toMap(t -> t.getEmail(),
                                    t -> t.getTotalOrders() + " / " + t.getTotalAmountSpent().stripTrailingZeros().toPlainString())),
                    coupons.stream().mapToLong(m -> m.getTotalUsages()).sum(),
                    coupons.stream().map(m -> m.getRevenueGenerated()).reduce(BigDecimal.ZERO, BigDecimal::add),
                    couponUsages, couponRevenue, byRef);
        });
    }

    /** Field-by-field equality (BigDecimal by value), so two report snapshots can be compared. */
    private static void assertSameReports(Reports actual, Reports expected, String as) {
        assertThat(actual.totalRevenue()).as(as + " totalRevenue").isEqualByComparingTo(expected.totalRevenue());
        assertThat(actual.completedOrders()).as(as + " completedOrders").isEqualTo(expected.completedOrders());
        assertThat(actual.topProducts()).as(as + " topProducts").isEqualTo(expected.topProducts());
        assertThat(actual.itemsSold()).as(as + " itemsSold").isEqualTo(expected.itemsSold());
        assertThat(actual.analyticsRevenue()).as(as + " analyticsRevenue").isEqualByComparingTo(expected.analyticsRevenue());
        assertThat(actual.analyticsCogs()).as(as + " analyticsCogs").isEqualByComparingTo(expected.analyticsCogs());
        assertThat(actual.analyticsExpenses()).as(as + " analyticsExpenses").isEqualByComparingTo(expected.analyticsExpenses());
        assertThat(actual.dailyRevenue()).as(as + " dailyRevenue").isEqualByComparingTo(expected.dailyRevenue());
        assertThat(actual.topCustomers()).as(as + " topCustomers").isEqualTo(expected.topCustomers());
        assertThat(actual.couponListUsages()).as(as + " couponListUsages").isEqualTo(expected.couponListUsages());
        assertThat(actual.couponListRevenue()).as(as + " couponListRevenue").isEqualByComparingTo(expected.couponListRevenue());
        assertThat(actual.couponUsages()).as(as + " couponUsages").isEqualTo(expected.couponUsages());
        assertThat(actual.couponRevenue()).as(as + " couponRevenue").isEqualByComparingTo(expected.couponRevenue());
        assertThat(actual.soldByReference()).as(as + " soldByReference").isEqualTo(expected.soldByReference());
    }

    private <T> List<T> concurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> fs = new ArrayList<>();
            for (Callable<T> t : tasks) fs.add(pool.submit(() -> { go.await(); return t.call(); }));
            go.countDown();
            List<T> out = new ArrayList<>();
            // ORD-02b: bounded, so a task stuck on a held row lock fails the test instead of hanging the suite
            for (Future<T> f : fs) out.add(f.get(90, java.util.concurrent.TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------------------------------ item 1: reversal

    // ------------------------------------------------- rework F1: ledger balances under concurrent postings

    /**
     * Per account category: stored balance minus the normal-side sum of all its journal lines. The fixture moves balances
     * without journal lines (and the class cleanup truncates the journal but keeps the balances), so a test compares
     * this drift before and after: a posting that loses a balance update changes it.
     */
    private Map<String, BigDecimal> drift() {
        Map<String, BigDecimal> out = new java.util.TreeMap<>();
        jdbc.query("SELECT a.category, a.balance - coalesce((SELECT sum(CASE WHEN (a.type IN ('ASSET', 'EXPENSE')) = (l.entry_type = 'DEBIT') "
                + "THEN l.amount ELSE -l.amount END) FROM tenant_acme.journal_entry_lines l WHERE l.account_id = a.id), 0) "
                + "FROM tenant_acme.ledger_accounts a", (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                out.put(rs.getString(1), rs.getBigDecimal(2)));
        return out;
    }

    private void assertNoDrift(Map<String, BigDecimal> before, String as) {
        Map<String, BigDecimal> after = drift();
        Map<String, BigDecimal> changed = new java.util.TreeMap<>();
        after.forEach((k, v) -> {
            BigDecimal d = v.subtract(before.getOrDefault(k, BigDecimal.ZERO));
            if (d.signum() != 0) changed.put(k, d);
        });
        assertThat(changed).as(as + ": every ledger balance moves exactly by its journal lines").isEmpty();
    }

    /** "200", or the status and body of any other response (so a failure shows why). */
    private static String outcome(MvcResult r) throws Exception {
        return status(r) == 200 ? "200" : status(r) + " " + r.getResponse().getContentAsString();
    }

    private List<Callable<String>> cancelTasks(List<Placed> orders) {
        List<Callable<String>> t = new ArrayList<>();
        for (Placed o : orders) t.add(() -> outcome(setStatus(o.orderNumber(), "CANCELLED")));
        return t;
    }


    // ------------------------------------------------------------------------------------------------- ORD-02b

    /** Every journal entry of the tenant balances (sum of debits = sum of credits per entry, hence overall). */
    private void assertJournalBalanced(String as) {
        List<Map<String, Object>> unbalanced = jdbc.queryForList("SELECT j.id, j.type FROM tenant_acme.journal_entries j "
                + "JOIN tenant_acme.journal_entry_lines l ON l.journal_entry_id = j.id GROUP BY j.id, j.type "
                + "HAVING sum(CASE WHEN l.entry_type = 'DEBIT' THEN l.amount ELSE -l.amount END) <> 0");
        assertThat(unbalanced).as(as + ": unbalanced journal entries").isEmpty();
        assertThat(jdbc.queryForObject("SELECT coalesce(sum(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END), 0) "
                + "FROM tenant_acme.journal_entry_lines", BigDecimal.class)).as(as + ": total debits = total credits").isEqualByComparingTo("0");
    }

    private Map<String, Object> couponRow(String code) {
        return jdbc.queryForMap("SELECT usage_count, is_active, limit_deactivated FROM tenant_acme.coupons WHERE code = ?", code);
    }

    private boolean released(String orderNumber) {
        return jdbc.queryForObject("SELECT coupon_redemption_released FROM tenant_acme.orders WHERE order_number = ?", Boolean.class, orderNumber);
    }

    private boolean refundRequired(String orderNumber) {
        return jdbc.queryForObject("SELECT p.refund_required FROM tenant_acme.payments p JOIN tenant_acme.orders o ON o.id = p.order_id "
                + "WHERE o.order_number = ?", Boolean.class, orderNumber);
    }

    /** n deliveries of the same charge.success at once, each from a background (webhook executor) thread. */
    private List<Integer> duplicateDeliveries(Gateway g, Placed o, int n) throws Exception {
        long m = minor(o.orderNumber());
        List<Future<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) fs.add(webhookInBackground(g, event("charge.success", o.reference(), m)));
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : fs) codes.add(f.get(90, java.util.concurrent.TimeUnit.SECONDS));
        return codes;
    }

    @Test

    private String detailSpent(Customer c) throws Exception {
        MvcResult r = mvc.perform(admin(get("/api/v1/admin/metrics/customers/" + c.id()))).andReturn();
        assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r).at("/data/totalSpent").asText();
    }

    private String listedSpent(Customer c) throws Exception {
        MvcResult r = mvc.perform(admin(get("/api/v1/admin/metrics/customers?page=0&size=50"))).andReturn();
        assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(200);
        for (JsonNode x : body(r).at("/data/content")) {
            if (x.get("email").asText().equals(c.email())) return x.get("totalSpent").asText();
        }
        throw new AssertionError("customer not listed: " + body(r));
    }

    // ------------------------------------------------------------------------------------------------- ORD-07 helpers

    private MvcResult returnOrder(String orderNumber) throws Exception {
        return mvc.perform(admin(post("/api/v1/order/" + orderNumber + "/return"))).andReturn();
    }

    private Placed placeDelivered(String email, int qty, long... products) throws Exception {
        return deliver(placePaid(email, qty, products));
    }

    private Placed deliver(Placed o) throws Exception {
        assertThat(status(setStatus(o.orderNumber(), "DELIVERED"))).isEqualTo(200);
        return o;
    }

    private int auditRows(String orderNumber, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM tenant_acme.order_audit_events e JOIN tenant_acme.orders o ON o.id = e.order_id "
                + "WHERE o.order_number = ? AND e.event_type = ?", Integer.class, orderNumber, type);
    }

    private String deliveryStatus(String orderNumber) {
        return jdbc.queryForObject("SELECT delivery_status FROM tenant_acme.orders WHERE order_number = ?", String.class, orderNumber);
    }

    private MvcResult customerDetail(Placed o) throws Exception {
        return mvc.perform(as(get("/api/v1/order/" + o.orderNumber()), o.c())).andReturn();
    }

    private void moveFulfilmentSource(long to) {
        jdbc.update("UPDATE tenant_acme.storage_locations SET is_ecommerce_fulfilment_source = false WHERE is_ecommerce_fulfilment_source");
        jdbc.update("UPDATE tenant_acme.storage_locations SET is_ecommerce_fulfilment_source = true WHERE id = ?", to);
    }

    private int soldCount(long productId) {
        return jdbc.queryForObject("SELECT coalesce(sold_count, 0) FROM tenant_acme.products WHERE id = ?", Integer.class, productId);
    }

    private int stockQuantity(long productId) {
        return jdbc.queryForObject("SELECT stock_quantity FROM tenant_acme.products WHERE id = ?", Integer.class, productId);
    }

    // ----------------------------------------------------------------------------------------------------- effects

    @Test
    void returnMarksTheOrderReturnedRequiresARefundAndKeepsThePaymentCompleted() throws Exception {
        Placed o = placeDelivered("r1@acme.test", 2);
        assertThat(jdbc.queryForObject("SELECT delivered_at FROM tenant_acme.orders WHERE order_number = ?", java.sql.Timestamp.class, o.orderNumber()))
                .as("delivered_at is stamped when the order is marked DELIVERED").isNotNull();
        long versionBefore = body(mvc.perform(admin(get("/api/v1/order/" + o.orderNumber()))).andReturn()).at("/data/version").asLong();

        MvcResult r = returnOrder(o.orderNumber());
        assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode d = body(r).get("data");
        assertThat(d.get("deliveryStatus").asText()).isEqualTo("RETURNED");
        assertThat(d.get("paymentStatus").asText()).isEqualTo("COMPLETED");
        assertThat(d.get("refundRequired").asBoolean()).isTrue();
        assertThat(d.get("returnedAt").asText()).endsWith("Z");
        assertThat(d.get("version").asLong()).isGreaterThan(versionBefore);
        assertThat(r.getResponse().getHeader("ETag")).isNotNull();
        assertThat(deliveryStatus(o.orderNumber())).isEqualTo("RETURNED");
        assertThat(jdbc.queryForObject("SELECT returned_by FROM tenant_acme.orders WHERE order_number = ?", String.class, o.orderNumber()))
                .isEqualTo(acme.adminEmail());
        assertThat(refundRequired(o.orderNumber())).isTrue();

        // RETURNED is final and only reachable through the return route
        MvcResult cancel = setStatus(o.orderNumber(), "CANCELLED");
        assertThat(status(cancel)).isEqualTo(400);
        assertThat(code(cancel)).isEqualTo("ORDER_FINAL_STATE");
        assertThat(status(setStatus(o.orderNumber(), "PACKING"))).isEqualTo(400);
        assertThat(status(setStatus(o.orderNumber(), "RETURNED"))).as("same status: no-op").isEqualTo(200);
        assertThat(status(cancel(o.c(), o.orderNumber()))).isEqualTo(409);
        MvcResult rr = returnReceived(o.orderNumber());
        assertThat(status(rr)).isEqualTo(409);
        assertThat(code(rr)).isEqualTo("RETURN_NOT_APPLICABLE");
    }

    @Test
    void returnRestocksToTheDeductionLocationEvenAfterTheFulfilmentSourceMoved() throws Exception {
        long original = fulfilmentSource();
        Placed o = placeDelivered("r2@acme.test", 3);
        long p = o.products().get(0);
        int soldBefore = soldCount(p);
        int stockBefore = stockQuantity(p);
        long other = jdbc.queryForObject("INSERT INTO tenant_acme.storage_locations (name, type, active, is_default_receiving, is_walk_in_sale_source, "
                + "is_ecommerce_fulfilment_source, version, created_at) VALUES ('Dispatch ' || gen_random_uuid(), 'STORE_ROOM', true, false, false, false, 0, now()) RETURNING id", Long.class);
        try {
            moveFulfilmentSource(other);
            assertThat(onHand(p, original)).isEqualTo(-3);
            assertThat(layerRemaining(acme, p)).isEqualTo(7);
            assertThat(status(returnOrder(o.orderNumber()))).isEqualTo(200);

            assertThat(onHand(p, original)).as("restocked where the units were deducted").isZero();
            assertThat(onHand(p, other)).as("not at the moved fulfilment source").isZero();
            assertThat(jdbc.queryForObject("SELECT to_location_id FROM tenant_acme.stock_transfers WHERE reference_id = ? AND transfer_type = 'SALE_RETURN'",
                    Long.class, o.orderNumber())).isEqualTo(original);
            assertThat(ledger(o.orderNumber(), "SALE_RETURN")).isEqualTo(3);
            assertThat(movements("RETURN", o.orderNumber())).isEqualTo(1);
            assertThat(layerRemaining(acme, p)).as("inventory layer").isEqualTo(10);
            assertThat(stockQuantity(p)).as("product stock").isEqualTo(stockBefore + 3);
            assertThat(soldCount(p)).as("soldCount").isEqualTo(soldBefore - 3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.inventory_allocations WHERE reference_id = ? AND status = 'CONSUMED'",
                    Integer.class, o.orderNumber())).as("nothing left consumed").isZero();
        } finally {
            moveFulfilmentSource(original);
        }
        // a repeat restocks nothing more
        assertThat(status(returnOrder(o.orderNumber()))).isEqualTo(200);
        assertThat(onHand(p, original)).isZero();
        assertThat(ledger(o.orderNumber(), "SALE_RETURN")).isEqualTo(3);
        assertThat(movements("RETURN", o.orderNumber())).isEqualTo(1);
    }

    @Test
    void returnReversesBooksAnalyticsAndRevenueReportsAndTheRefundAckPaysItOut() throws Exception {
        String emptyCard = analyticsRevenueCard();
        Placed o = placeDelivered("r3@acme.test", 3);
        BigDecimal total = total(o.orderNumber());
        Reports sold = reports();
        assertThat(sold.totalRevenue()).isEqualByComparingTo(total);
        assertThat(sold.completedOrders()).isEqualTo(1);
        assertThat(sold.topProducts()).isEqualTo(1);
        assertThat(sold.itemsSold()).isEqualTo(3);
        assertThat(sold.analyticsRevenue()).isEqualByComparingTo("30.00");
        assertThat(sold.analyticsCogs()).isEqualByComparingTo("12.00");
        assertThat(analyticsRevenueCard()).isNotEqualTo(emptyCard);
        String spent = detailSpent(o.c());
        BigDecimal cashBefore = accountBalance("CASH");
        BigDecimal refundsBefore = accountBalance("REFUND_PAYABLE");
        Map<String, BigDecimal> drift = drift();

        assertThat(status(returnOrder(o.orderNumber()))).isEqualTo(200);

        assertThat(entries(o.orderNumber(), "SALE_REVERSAL")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT description FROM tenant_acme.journal_entries WHERE reference_id = ? AND type = 'SALE_REVERSAL'",
                String.class, o.orderNumber())).startsWith("Sale reversed for returned order");
        assertThat(net(o.orderNumber(), "SALES_REVENUE")).isEqualByComparingTo("0");
        assertThat(net(o.orderNumber(), "TAX_PAYABLE")).isEqualByComparingTo("0");
        assertThat(net(o.orderNumber(), "COGS")).as("goods are back: COGS reversed").isEqualByComparingTo("0");
        assertThat(net(o.orderNumber(), "INVENTORY")).as("goods are back: inventory reversed").isEqualByComparingTo("0");
        assertThat(net(o.orderNumber(), "REFUND_PAYABLE")).isEqualByComparingTo(total);
        assertThat(accountBalance("CASH")).as("cash leaves only when refunded").isEqualByComparingTo(cashBefore);
        assertThat(accountBalance("REFUND_PAYABLE")).isEqualByComparingTo(refundsBefore.add(total));

        Reports after = reports();
        assertThat(after.totalRevenue()).isEqualByComparingTo("0");
        assertThat(after.dailyRevenue()).isEqualByComparingTo("0");
        assertThat(after.completedOrders()).isZero();
        assertThat(after.topProducts()).isZero();
        assertThat(after.itemsSold()).isZero();
        assertThat(after.topCustomers()).isEmpty();
        assertThat(after.soldByReference()).isEmpty();
        assertThat(after.analyticsRevenue()).isEqualByComparingTo("0");
        assertThat(after.analyticsCogs()).isEqualByComparingTo("0");
        assertThat(analyticsRevenueCard()).isEqualTo(emptyCard);
        assertThat(detailSpent(o.c())).as("customer details exclude the returned order").isNotEqualTo(spent);
        assertThat(listedSpent(o.c())).isEqualTo(detailSpent(o.c()));
        assertNoDrift(drift, "return");
        assertJournalBalanced("return");

        // the refund acknowledgement settles Refunds Payable against Cash once
        MvcResult a = ack(o.orderNumber());
        assertThat(status(a)).as(a.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(a).at("/data/refundRequired").asBoolean()).isFalse();
        assertThat(body(a).at("/data/deliveryStatus").asText()).isEqualTo("RETURNED");
        assertThat(entries(o.orderNumber(), "REFUND")).isEqualTo(1);
        assertThat(net(o.orderNumber(), "REFUND_PAYABLE")).isEqualByComparingTo("0");
        assertThat(net(o.orderNumber(), "CASH")).isEqualByComparingTo("0");
        assertThat(accountBalance("CASH")).isEqualByComparingTo(cashBefore.subtract(total));
        assertThat(auditRows(o.orderNumber(), "REFUND_ACKNOWLEDGED")).isEqualTo(1);
        MvcResult again = ack(o.orderNumber());
        assertThat(status(again)).isEqualTo(409);
        assertThat(code(again)).isEqualTo("REFUND_NOT_REQUIRED");
        assertNoDrift(drift, "return + refund");
        assertJournalBalanced("return + refund");
    }

    @Test
    void returnWithTooLittleCashStillBooksTheReversalButTheAckIsRefusedUntilCashExists() throws Exception {
        Placed o = placeDelivered("r4@acme.test", 2);
        BigDecimal total = total(o.orderNumber());
        assertThat(status(returnOrder(o.orderNumber()))).isEqualTo(200);
        BigDecimal cash = accountBalance("CASH");
        BigDecimal low = total.subtract(new BigDecimal("0.01"));
        jdbc.update("UPDATE tenant_acme.ledger_accounts SET balance = ? WHERE category = 'CASH'", low);
        try {
            MvcResult r = ack(o.orderNumber());
            assertThat(status(r)).isEqualTo(409);
            assertThat(code(r)).isEqualTo("INSUFFICIENT_CASH");
            assertThat(refundRequired(o.orderNumber())).isTrue();
            assertThat(entries(o.orderNumber(), "REFUND")).isZero();
        } finally {
            jdbc.update("UPDATE tenant_acme.ledger_accounts SET balance = ? WHERE category = 'CASH'", cash);
        }
        assertThat(status(ack(o.orderNumber()))).isEqualTo(200);
    }

    @Test
    void returnGivesTheCouponRedemptionBackExactlyOnceUnderConcurrentReturns() throws Exception {
        coupon(acme, "ORD07", 10);
        jdbc.update("UPDATE tenant_acme.coupons SET usage_limit = 2, per_user_usage_limit = 1 WHERE code = 'ORD07'");
        long p = costedProduct("Return coupon", 20);
        Customer c = customer(acme, "rc-1@acme.test");
        Placed kept = deliver(settle(placeAs(customer(acme, "rc-2@acme.test"), "couponCode=ORD07", 1, p)));
        Placed o = deliver(settle(placeAs(c, "couponCode=ORD07", 2, p)));
        assertThat(couponRow("ORD07")).containsEntry("usage_count", 2).containsEntry("is_active", false);
        BigDecimal discount = net(o.orderNumber(), "DISCOUNT_EXPENSE");
        assertThat(discount).as("the sale booked a coupon discount").isNotEqualByComparingTo("0");

        Map<String, BigDecimal> before = drift();
        assertThat(concurrently(List.of(() -> outcome(returnOrder(o.orderNumber())), () -> outcome(returnOrder(o.orderNumber())),
                () -> outcome(returnOrder(o.orderNumber()))))).containsOnly("200");
        assertThat(status(returnOrder(o.orderNumber()))).as("retry").isEqualTo(200);

        assertThat(released(o.orderNumber())).isTrue();
        assertThat(released(kept.orderNumber())).isFalse();
        assertThat(couponRow("ORD07")).as("one release only: the other redemption is still counted")
                .containsEntry("usage_count", 1).containsEntry("is_active", true).containsEntry("limit_deactivated", false);
        assertThat(net(o.orderNumber(), "DISCOUNT_EXPENSE")).as("discount reversed with the sale").isEqualByComparingTo("0");
        assertNoDrift(before, "return with a coupon");
        assertJournalBalanced("return with a coupon");
        // the per-user limit no longer counts the returned order
        Placed again = placeAs(c, "couponCode=ORD07", 1, p);
        assertThat(couponRow("ORD07")).containsEntry("usage_count", 2);
        assertThat(released(again.orderNumber())).isFalse();
    }

    @Test
    void returnWritesOneAuditRowAndADoubleSubmitChangesNothing() throws Exception {
        Placed o = placeDelivered("r5@acme.test", 2);
        long p = o.products().get(0);
        MvcResult first = returnOrder(o.orderNumber());
        assertThat(status(first)).isEqualTo(200);
        long version = body(first).at("/data/version").asLong();
        String returnedAt = body(first).at("/data/returnedAt").asText();
        assertThat(auditRows(o.orderNumber(), "RETURNED")).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT from_status, to_status, refund_required, actor_email FROM tenant_acme.order_audit_events "
                + "WHERE event_type = 'RETURNED' AND order_id = (SELECT id FROM tenant_acme.orders WHERE order_number = ?)", o.orderNumber()))
                .containsEntry("from_status", "DELIVERED").containsEntry("to_status", "RETURNED")
                .containsEntry("refund_required", true).containsEntry("actor_email", acme.adminEmail());

        MvcResult second = returnOrder(o.orderNumber());
        assertThat(status(second)).as("double submit is a 200 no-op").isEqualTo(200);
        assertThat(body(second).at("/data/version").asLong()).isEqualTo(version);
        assertThat(body(second).at("/data/returnedAt").asText()).isEqualTo(returnedAt);
        assertThat(auditRows(o.orderNumber(), "RETURNED")).isEqualTo(1);
        assertThat(entries(o.orderNumber(), "SALE_REVERSAL")).isEqualTo(1);
        assertThat(movements("RETURN", o.orderNumber())).isEqualTo(1);
        assertThat(layerRemaining(acme, p)).isEqualTo(10);

        // the audit read endpoint lists it
        MvcResult events = auditEvents(o.orderNumber(), null);
        List<String> types = new ArrayList<>();
        for (JsonNode e : body(events).at("/data/content")) types.add(e.get("eventType").asText());
        assertThat(types).containsExactly("STATUS_CHANGED", "RETURNED");
    }

    // -------------------------------------------------------------------------------------------------- validation

    @Test
    void onlyAPaidDeliveredOrderCanBeReturned() throws Exception {
        Placed packing = placePaid("v1@acme.test", 1);
        assertThat(status(setStatus(packing.orderNumber(), "PACKING"))).isEqualTo(200);
        MvcResult r = returnOrder(packing.orderNumber());
        assertThat(status(r)).isEqualTo(409);
        assertThat(code(r)).isEqualTo("ORDER_NOT_RETURNABLE");
        assertThat(body(r).at("/data/deliveryStatus").asText()).isEqualTo("PACKING");
        assertThat(body(r).at("/data/paymentStatus").asText()).isEqualTo("COMPLETED");

        Placed unpaid = place("v2@acme.test", 1);
        assertThat(code(returnOrder(unpaid.orderNumber()))).isEqualTo("ORDER_NOT_RETURNABLE");

        Placed cancelled = placePaid("v3@acme.test", 1);
        assertThat(status(setStatus(cancelled.orderNumber(), "CANCELLED"))).isEqualTo(200);
        assertThat(code(returnOrder(cancelled.orderNumber()))).isEqualTo("ORDER_NOT_RETURNABLE");

        // nothing changed for any of them
        assertThat(entries(packing.orderNumber(), "SALE_REVERSAL")).isZero();
        assertThat(entries(cancelled.orderNumber(), "SALE_REVERSAL")).isEqualTo(1);
        assertThat(auditRows(packing.orderNumber(), "RETURNED") + auditRows(unpaid.orderNumber(), "RETURNED")
                + auditRows(cancelled.orderNumber(), "RETURNED")).isZero();
        assertThat(deliveryStatus(packing.orderNumber())).isEqualTo("PACKING");
        assertThat(status(returnOrder("PB-NOPE-NOPE"))).isEqualTo(404);
        assertThat(code(returnOrder("PB-NOPE-NOPE"))).isEqualTo("ORDER_NOT_FOUND");
    }

    @Test
    void anUnpaidOrderThatSomehowIsDeliveredCannotBeReturned() throws Exception {
        Placed unpaid = place("up1@acme.test", 1);
        jdbc.update("UPDATE tenant_acme.orders SET delivery_status = 'DELIVERED' WHERE order_number = ?", unpaid.orderNumber());
        MvcResult r = returnOrder(unpaid.orderNumber());
        assertThat(status(r)).isEqualTo(409);
        assertThat(code(r)).isEqualTo("ORDER_NOT_RETURNABLE");
        assertThat(body(r).at("/data/paymentStatus").asText()).isNotEqualTo("COMPLETED");
        assertThat(deliveryStatus(unpaid.orderNumber())).isEqualTo("DELIVERED");
        assertThat(entries(unpaid.orderNumber(), "SALE_REVERSAL")).isZero();
    }

    @Test
    void theItemsSoldReportAndItsPerProductViewDropAReturnedOrder() throws Exception {
        Placed o = placeDelivered("is1@acme.test", 2);
        long p = o.products().get(0);
        String name = "Rev is1@acme.test";
        MvcResult before = mvc.perform(admin(get("/api/v1/admin/inventory/sales/items-sold?source=ONLINE"))).andReturn();
        assertThat(status(before)).isEqualTo(200);
        assertThat(before.getResponse().getContentAsString()).contains(name);
        MvcResult perProductBefore = mvc.perform(admin(get("/api/v1/admin/inventory/sales/items-sold/" + p + "?source=ONLINE"))).andReturn();
        assertThat(body(perProductBefore).at("/data/totalQuantitySold").asInt()).isEqualTo(2);
        assertThat(status(returnOrder(o.orderNumber()))).isEqualTo(200);
        MvcResult after = mvc.perform(admin(get("/api/v1/admin/inventory/sales/items-sold?source=ONLINE"))).andReturn();
        assertThat(status(after)).isEqualTo(200);
        assertThat(after.getResponse().getContentAsString()).doesNotContain(name);
        MvcResult perProductAfter = mvc.perform(admin(get("/api/v1/admin/inventory/sales/items-sold/" + p + "?source=ONLINE"))).andReturn();
        // the per-product list is the stock ledger (the sale row stays, with amount 0); the totals drop the order
        assertThat(body(perProductAfter).at("/data/totalQuantitySold").asInt()).isZero();
    }

    @Test
    void theDeliveredOrderStillAnswersTheOldWayForTheOtherRoutes() throws Exception {
        Placed o = placeDelivered("v4@acme.test", 1);
        MvcResult cancel = setStatus(o.orderNumber(), "CANCELLED");
        assertThat(status(cancel)).isEqualTo(400);
        assertThat(code(cancel)).isEqualTo("ORDER_FINAL_STATE");
        MvcResult toReturned = setStatus(o.orderNumber(), "RETURNED");
        assertThat(status(toReturned)).as("RETURNED is not a status change").isEqualTo(400);
        assertThat(code(toReturned)).isEqualTo("ORDER_FINAL_STATE");
        assertThat(code(returnReceived(o.orderNumber()))).isEqualTo("RETURN_NOT_APPLICABLE");
        assertThat(code(ack(o.orderNumber()))).isEqualTo("REFUND_NOT_REQUIRED");
        assertThat(code(cancel(o.c(), o.orderNumber()))).isEqualTo("ORDER_NOT_CANCELLABLE");
        Placed early = place("v5@acme.test", 1);
        MvcResult toReturnedEarly = setStatus(early.orderNumber(), "RETURNED");
        assertThat(status(toReturnedEarly)).isEqualTo(400);
        assertThat(code(toReturnedEarly)).isEqualTo("ORDER_TRANSITION_NOT_ALLOWED");
        assertThat(entries(o.orderNumber(), "SALE_REVERSAL")).isZero();
    }

    @Test
    void theReturnRouteIsAdminOnlyAndTenantScoped() throws Exception {
        Placed o = placeDelivered("v6@acme.test", 1);
        String url = "/api/v1/order/" + o.orderNumber() + "/return";
        assertThat(status(mvc.perform(post(url).header("X-Tenant-ID", "acme")).andReturn())).isEqualTo(401);
        assertThat(status(mvc.perform(as(post(url), o.c())).andReturn())).isEqualTo(403);
        assertThat(status(mvc.perform(bravoAdminOnAcme(post(url))).andReturn())).isEqualTo(401);
        assertThat(deliveryStatus(o.orderNumber())).isEqualTo("DELIVERED");
        long bp = product(bravo, "Bravo return", "5.00", 5);
        Customer bc = customer(bravo, "v6@bravo.test");
        cart(bc, bp, 1);
        String bravoOrder = body(checkout(bc, null, null)).at("/data/orderNumber").asText();
        assertThat(status(returnOrder(bravoOrder))).isEqualTo(404);
    }

    // ---------------------------------------------------------------------------------------------- return window

    private void withWindow(Integer days, org.junit.jupiter.api.function.ThrowingSupplier<Object> body) throws Throwable {
        Object policy = returnPolicy;
        Object old = org.springframework.test.util.ReflectionTestUtils.getField(policy, "windowDays");
        org.springframework.test.util.ReflectionTestUtils.setField(policy, "windowDays", days);
        try {
            body.get();
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(policy, "windowDays", old);
        }
    }

    @Test
    void theOptionalReturnWindowIsEnforcedFromTheDeliveryTimeAndOffByDefault() throws Throwable {
        Placed o = placeDelivered("w1@acme.test", 1);
        jdbc.update("UPDATE tenant_acme.orders SET delivered_at = now() - interval '10 days' WHERE order_number = ?", o.orderNumber());
        // default: no window, a 10-day-old delivery can be returned (checked last, on a second order)
        Placed fresh = placeDelivered("w2@acme.test", 1);
        Placed old = placeDelivered("w3@acme.test", 1);
        jdbc.update("UPDATE tenant_acme.orders SET delivered_at = now() - interval '400 days' WHERE order_number = ?", old.orderNumber());
        Placed legacy = placeDelivered("w4@acme.test", 1);
        jdbc.update("UPDATE tenant_acme.orders SET delivered_at = NULL WHERE order_number = ?", legacy.orderNumber());

        withWindow(7, () -> {
            MvcResult expired = returnOrder(o.orderNumber());
            assertThat(status(expired)).isEqualTo(409);
            assertThat(code(expired)).isEqualTo("RETURN_WINDOW_EXPIRED");
            assertThat(body(expired).at("/data/returnWindowDays").asInt()).isEqualTo(7);
            assertThat(body(expired).at("/data/deadline").asText()).endsWith("Z");
            assertThat(deliveryStatus(o.orderNumber())).isEqualTo("DELIVERED");
            assertThat(entries(o.orderNumber(), "SALE_REVERSAL")).isZero();
            assertThat(movements("RETURN", o.orderNumber())).isZero();
            assertThat(status(returnOrder(fresh.orderNumber()))).as("inside the window").isEqualTo(200);
            assertThat(status(returnOrder(legacy.orderNumber()))).as("unknown delivery time: no deadline").isEqualTo(200);
            return null;
        });
        withWindow(30, () -> {
            assertThat(status(returnOrder(o.orderNumber()))).as("10 days old inside a 30-day window").isEqualTo(200);
            assertThat(code(returnOrder(old.orderNumber()))).isEqualTo("RETURN_WINDOW_EXPIRED");
            return null;
        });
        assertThat(status(returnOrder(old.orderNumber()))).as("default: no window").isEqualTo(200);
    }

    @Test
    void anAlreadyReturnedOrderIsNotRefusedByAWindowThatClosedLater() throws Exception {
        Placed o = placeDelivered("w5@acme.test", 1);
        assertThat(status(returnOrder(o.orderNumber()))).isEqualTo(200);
        jdbc.update("UPDATE tenant_acme.orders SET delivered_at = now() - interval '400 days' WHERE order_number = ?", o.orderNumber());
        org.springframework.test.util.ReflectionTestUtils.setField(returnPolicy, "windowDays", 7);
        try {
            assertThat(status(returnOrder(o.orderNumber()))).isEqualTo(200);
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(returnPolicy, "windowDays", null);
        }
    }

    // ----------------------------------------------------------------------------------------------- customer view

    @Test
    void theCustomerSeesTheReturnedAndRefundState() throws Exception {
        Placed o = placeDelivered("c1@acme.test", 1);
        JsonNode before = body(customerDetail(o)).get("data");
        assertThat(before.get("deliveryStatus").asText()).isEqualTo("DELIVERED");
        assertThat(before.get("refundRequired").asBoolean()).isFalse();
        assertThat(before.get("returnedAt").isNull()).isTrue();

        assertThat(status(returnOrder(o.orderNumber()))).isEqualTo(200);
        MvcResult r = customerDetail(o);
        assertThat(status(r)).isEqualTo(200);
        JsonNode d = body(r).get("data");
        assertThat(d.get("deliveryStatus").asText()).isEqualTo("RETURNED");
        assertThat(d.get("refundRequired").asBoolean()).as("refund pending").isTrue();
        assertThat(d.get("returnedAt").asText()).endsWith("Z");
        assertThat(d.get("cancellable").asBoolean()).isFalse();
        assertThat(d.has("account")).as("customers never get the account block").isFalse();

        assertThat(status(ack(o.orderNumber()))).isEqualTo(200);
        JsonNode refunded = body(customerDetail(o)).get("data");
        assertThat(refunded.get("deliveryStatus").asText()).isEqualTo("RETURNED");
        assertThat(refunded.get("refundRequired").asBoolean()).as("refunded").isFalse();

        // the customer's order list carries the same status
        MvcResult list = mvc.perform(as(get("/api/v1/order/my-orders"), o.c())).andReturn();
        assertThat(status(list)).isEqualTo(200);
        assertThat(list.getResponse().getContentAsString()).contains("RETURNED");
    }

    @Test
    void theV17BackfillStampsDeliveredAtFromTheAuditTrailAndIsIdempotent() throws Exception {
        Placed viaAudit = placeDelivered("m1@acme.test", 1);
        Placed noAudit = placeDelivered("m2@acme.test", 1);
        Placed inProgress = placePaid("m3@acme.test", 1);
        jdbc.update("UPDATE tenant_acme.orders SET delivered_at = NULL");
        jdbc.execute("ALTER TABLE tenant_acme.order_audit_events DISABLE TRIGGER USER");
        try {
            jdbc.update("DELETE FROM tenant_acme.order_audit_events WHERE order_id = (SELECT id FROM tenant_acme.orders WHERE order_number = ?)", noAudit.orderNumber());
        } finally {
            jdbc.execute("ALTER TABLE tenant_acme.order_audit_events ENABLE TRIGGER USER");
        }
        java.sql.Timestamp auditAt = jdbc.queryForObject("SELECT max(e.created_at) FROM tenant_acme.order_audit_events e JOIN tenant_acme.orders o ON o.id = e.order_id "
                + "WHERE o.order_number = ? AND e.to_status = 'DELIVERED'", java.sql.Timestamp.class, viaAudit.orderNumber());
        runV17Backfill();
        assertThat(jdbc.queryForObject("SELECT delivered_at FROM tenant_acme.orders WHERE order_number = ?", java.sql.Timestamp.class, viaAudit.orderNumber()))
                .isEqualTo(auditAt);
        assertThat(jdbc.queryForObject("SELECT delivered_at FROM tenant_acme.orders WHERE order_number = ?", java.sql.Timestamp.class, noAudit.orderNumber()))
                .as("no audit row: the last update of the order").isNotNull();
        assertThat(jdbc.queryForObject("SELECT delivered_at FROM tenant_acme.orders WHERE order_number = ?", java.sql.Timestamp.class, inProgress.orderNumber()))
                .as("not delivered: untouched").isNull();
        jdbc.update("UPDATE tenant_acme.orders SET delivered_at = delivered_at + interval '1 hour' WHERE order_number = ?", viaAudit.orderNumber());
        java.sql.Timestamp moved = jdbc.queryForObject("SELECT delivered_at FROM tenant_acme.orders WHERE order_number = ?", java.sql.Timestamp.class, viaAudit.orderNumber());
        runV17Backfill();
        assertThat(jdbc.queryForObject("SELECT delivered_at FROM tenant_acme.orders WHERE order_number = ?", java.sql.Timestamp.class, viaAudit.orderNumber()))
                .as("idempotent: a value already there is kept").isEqualTo(moved);
    }

    private void runV17Backfill() throws Exception {
        String sql = new String(getClass().getResourceAsStream("/db/migrations/tenant/V17__delivered_order_returns.sql").readAllBytes());
        String stmt = sql.substring(sql.indexOf("UPDATE orders o"), sql.indexOf(";", sql.indexOf("UPDATE orders o")));
        jdbc.execute(stmt.replace("UPDATE orders o", "UPDATE tenant_acme.orders o")
                .replace("FROM order_audit_events e", "FROM tenant_acme.order_audit_events e"));
    }

    // ------------------------------------------------------------------------------------------------ concurrency

    @Test
    void simultaneousReturnsAcksAndStatusChangesOfOneOrderApplyEverythingOnce() throws Exception {
        for (int round = 0; round < 3; round++) {
            Placed o = placeDelivered("cc" + round + "@acme.test", 2);
            BigDecimal total = total(o.orderNumber());
            Map<String, BigDecimal> before = drift();
            List<String> out = concurrently(List.of(
                    () -> outcome(returnOrder(o.orderNumber())), () -> outcome(returnOrder(o.orderNumber())),
                    () -> outcome(ack(o.orderNumber())), () -> outcome(setStatus(o.orderNumber(), "CANCELLED")),
                    () -> outcome(returnReceived(o.orderNumber())), () -> outcome(returnOrder(o.orderNumber()))));
            assertThat(out.subList(0, 2)).containsOnly("200");
            assertThat(out.get(5)).isEqualTo("200");
            assertThat(out.get(2)).as("ack: refused before the return, done after").matches("200|409 .*REFUND_NOT_REQUIRED.*");
            assertThat(out.get(3)).as("status change on a DELIVERED/RETURNED order").startsWith("400").contains("ORDER_FINAL_STATE");
            assertThat(out.get(4)).as("return-received").startsWith("409").contains("RETURN_NOT_APPLICABLE");

            assertThat(deliveryStatus(o.orderNumber())).isEqualTo("RETURNED");
            assertThat(auditRows(o.orderNumber(), "RETURNED")).isEqualTo(1);
            assertThat(entries(o.orderNumber(), "SALE_REVERSAL")).isEqualTo(1);
            assertThat(movements("RETURN", o.orderNumber())).isEqualTo(1);
            assertThat(layerRemaining(acme, o.products().get(0))).isEqualTo(10);
            boolean acked = auditRows(o.orderNumber(), "REFUND_ACKNOWLEDGED") == 1;
            if (!acked) {
                assertThat(status(ack(o.orderNumber()))).isEqualTo(200);
            }
            assertThat(entries(o.orderNumber(), "REFUND")).isEqualTo(1);
            assertThat(net(o.orderNumber(), "REFUND_PAYABLE")).isEqualByComparingTo("0");
            assertThat(net(o.orderNumber(), "CASH")).isEqualByComparingTo("0");
            assertNoDrift(before, "round " + round);
            assertJournalBalanced("round " + round);
            assertThat(total).isPositive();
        }
    }

    @Test
    void manyReturnsAcksAndACheckoutOnSharedProductsNeverDeadlockAndBalance() throws Exception {
        long p1 = costedProduct("Shared return A", 40);
        long p2 = costedProduct("Shared return B", 40);
        List<Placed> orders = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            orders.add(deliver(settle(place("mx" + i + "@acme.test", 1, i % 2 == 0 ? new long[]{p1, p2} : new long[]{p2, p1}))));
        }
        Customer shopper = customer(acme, "mx-shopper@acme.test");
        cart(shopper, p1, 1);
        cart(shopper, p2, 1);
        Map<String, BigDecimal> before = drift();
        List<Callable<String>> tasks = new ArrayList<>();
        for (Placed o : orders) {
            tasks.add(() -> outcome(returnOrder(o.orderNumber())));
            tasks.add(() -> outcome(returnOrder(o.orderNumber())));
        }
        for (Placed o : orders) tasks.add(() -> { String r = outcome(ack(o.orderNumber())); return r.startsWith("200") || r.contains("REFUND_NOT_REQUIRED") ? "ok" : r; });
        tasks.add(() -> { MvcResult r = checkout(shopper, null, null); return status(r) == 200 ? "ok" : outcome(r); });
        List<String> out = concurrently(tasks);
        assertThat(out.subList(0, 12)).containsOnly("200");
        assertThat(out.subList(12, 19)).containsOnly("ok");
        for (Placed o : orders) {
            assertThat(deliveryStatus(o.orderNumber())).isEqualTo("RETURNED");
            assertThat(entries(o.orderNumber(), "SALE_REVERSAL")).isEqualTo(1);
            assertThat(movements("RETURN", o.orderNumber())).isEqualTo(2);
            if (refundRequired(o.orderNumber())) assertThat(status(ack(o.orderNumber()))).isEqualTo(200);
            assertThat(net(o.orderNumber(), "REFUND_PAYABLE")).isEqualByComparingTo("0");
        }
        assertNoDrift(before, "mixed return/ack/checkout");
        assertJournalBalanced("mixed return/ack/checkout");
    }

    @Test
    void aReturnWaitsForAHeldOrderRowLockWithABoundedWaitAndThenCompletes() throws Exception {
        Placed o = placeDelivered("lk@acme.test", 1);
        // another transaction holds the order row; the return runs in a bounded task and finishes once it lets go
        org.springframework.transaction.support.TransactionTemplate tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                jdbc.queryForList("SELECT id FROM tenant_acme.orders WHERE order_number = ? FOR UPDATE", o.orderNumber());
                locked.countDown();
                try {
                    if (!release.await(30, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("not released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            Future<Integer> ret = pool.submit(() -> status(returnOrder(o.orderNumber())));
            Thread.sleep(500);
            assertThat(ret.isDone()).as("the return waits for the order row lock").isFalse();
            assertThat(deliveryStatus(o.orderNumber())).isEqualTo("DELIVERED");
            release.countDown();
            holder.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(ret.get(60, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(deliveryStatus(o.orderNumber())).isEqualTo("RETURNED");
        assertThat(entries(o.orderNumber(), "SALE_REVERSAL")).isEqualTo(1);
    }
}
