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
    void unpaidCancelPostsNothing() throws Exception {
        Placed o = place("rv3@acme.test", 1);
        assertThat(status(setStatus(o.orderNumber(), "CANCELLED"))).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.journal_entries WHERE reference_id = ?", Integer.class, o.orderNumber())).isZero();
        assertThat(code(ack(o.orderNumber()))).isEqualTo("REFUND_NOT_REQUIRED");
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

    @Test
    void v14BackfillPicksTheNewestOfSeveralDeductions() throws Exception {
        long source = fulfilmentSource();
        Placed o = placePaid("rv9b@acme.test", 2);
        long p = o.products().get(0);
        long other = jdbc.queryForObject("INSERT INTO tenant_acme.storage_locations (name, type, active, is_default_receiving, is_walk_in_sale_source, "
                + "is_ecommerce_fulfilment_source, version, created_at) VALUES ('Backfill ' || gen_random_uuid(), 'STORE_ROOM', true, false, false, false, 0, now()) RETURNING id", Long.class);
        long third = jdbc.queryForObject("INSERT INTO tenant_acme.storage_locations (name, type, active, is_default_receiving, is_walk_in_sale_source, "
                + "is_ecommerce_fulfilment_source, version, created_at) VALUES ('Backfill ' || gen_random_uuid(), 'STORE_ROOM', true, false, false, false, 0, now()) RETURNING id", Long.class);
        // two later deductions of the same order and product (e.g. a re-fulfilment): the newest (highest id) is `other`
        for (long loc : new long[]{third, other}) {
            jdbc.update("INSERT INTO tenant_acme.stock_transfers (product_id, from_location_id, quantity, transfer_type, reference_type, reference_id, created_at) "
                    + "VALUES (?, ?, 2, 'SALE_DEDUCTION', 'ORDER', ?, now())", p, loc, o.orderNumber());
        }
        // a newer deduction of ANOTHER order of the same product must not be picked
        jdbc.update("INSERT INTO tenant_acme.stock_transfers (product_id, from_location_id, quantity, transfer_type, reference_type, reference_id, created_at) "
                + "VALUES (?, ?, 1, 'SALE_DEDUCTION', 'ORDER', 'PB-OTHER', now())", p, source);
        jdbc.update("UPDATE tenant_acme.inventory_allocations SET fulfilment_location_id = NULL, fulfilment_location_recorded = false WHERE reference_id = ?",
                o.orderNumber());
        String sql = new String(getClass().getResourceAsStream("/db/migrations/tenant/V14__paid_cancel_reversal.sql").readAllBytes());
        String update = sql.substring(sql.indexOf("UPDATE inventory_allocations"), sql.indexOf(";", sql.indexOf("UPDATE inventory_allocations")));
        jdbc.execute(update.replace("UPDATE inventory_allocations", "UPDATE tenant_acme.inventory_allocations")
                .replace("FROM stock_transfers", "FROM tenant_acme.stock_transfers"));
        assertThat(jdbc.queryForObject("SELECT fulfilment_location_id FROM tenant_acme.inventory_allocations WHERE reference_id = ?",
                Long.class, o.orderNumber())).as("newest deduction wins").isEqualTo(other);
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
        assertThat(body(auditEvents(o.orderNumber(), "size=100000")).at("/data/size").asInt()).isEqualTo(50);
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

    private List<Callable<String>> ackTasks(List<Placed> orders) {
        List<Callable<String>> t = new ArrayList<>();
        for (Placed o : orders) t.add(() -> outcome(ack(o.orderNumber())));
        return t;
    }

    private List<Callable<String>> webhookTasks(Gateway g, List<Placed> orders) {
        List<Callable<String>> t = new ArrayList<>();
        for (Placed o : orders) {
            long m = minor(o.orderNumber());
            t.add(() -> String.valueOf(webhook(g, event("charge.success", o.reference(), m))));
        }
        return t;
    }

    private void awaitSettled(List<Placed> orders) throws Exception {
        await().atMost(java.time.Duration.ofSeconds(30)).until(() ->
                orders.stream().allMatch(o -> entries(o.orderNumber(), "SALE") == 1));
        awaitBackgroundIdle();
    }

    @Test
    void concurrentPaidCancelsThenConcurrentRefundAcksKeepEveryBalanceEqualToItsJournal() throws Exception {
        int n = 6;
        for (int round = 0; round < 2; round++) {
            List<Placed> paid = new ArrayList<>();
            for (int i = 0; i < n; i++) paid.add(placePaid("lb-c" + round + "-" + i + "@acme.test", 2));
            Map<String, BigDecimal> before = drift();

            assertThat(concurrently(cancelTasks(paid))).as("round " + round).containsOnly("200");
            for (Placed o : paid) assertThat(entries(o.orderNumber(), "SALE_REVERSAL")).isEqualTo(1);
            assertNoDrift(before, "round " + round + " after " + n + " concurrent paid cancels");

            assertThat(concurrently(ackTasks(paid))).as("round " + round).containsOnly("200");
            for (Placed o : paid) assertThat(entries(o.orderNumber(), "REFUND")).isEqualTo(1);
            assertNoDrift(before, "round " + round + " after " + n + " concurrent refund acks");
        }
    }

    @Test
    void concurrentSuccessWebhooksKeepEveryBalanceEqualToItsJournal() throws Exception {
        Gateway g = configureGateway(acme);
        int n = 6;
        for (int round = 0; round < 2; round++) {
            List<Placed> pending = new ArrayList<>();
            for (int i = 0; i < n; i++) pending.add(place("lb-w" + round + "-" + i + "@acme.test", 2));
            Map<String, BigDecimal> before = drift();
            assertThat(concurrently(webhookTasks(g, pending))).as("round " + round).containsOnly("200");
            awaitSettled(pending);
            assertNoDrift(before, "round " + round + " after " + n + " concurrent success webhooks");
        }
    }

    @Test
    void mixedConcurrentCancelsAcksAndWebhooksKeepEveryBalanceEqualToItsJournal() throws Exception {
        Gateway g = configureGateway(acme);
        for (int round = 0; round < 2; round++) {
            List<Placed> toCancel = new ArrayList<>();
            List<Placed> toAck = new ArrayList<>();
            List<Placed> pending = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                toCancel.add(placePaid("lb-mc" + round + "-" + i + "@acme.test", 2));
                Placed a = placePaid("lb-ma" + round + "-" + i + "@acme.test", 1);
                assertThat(status(setStatus(a.orderNumber(), "CANCELLED"))).isEqualTo(200);
                toAck.add(a);
                pending.add(place("lb-mw" + round + "-" + i + "@acme.test", 3));
            }
            Map<String, BigDecimal> before = drift();
            List<Callable<String>> tasks = new ArrayList<>();
            List<Callable<String>> c = cancelTasks(toCancel), a = ackTasks(toAck), w = webhookTasks(g, pending);
            for (int i = 0; i < 3; i++) { tasks.add(c.get(i)); tasks.add(a.get(i)); tasks.add(w.get(i)); }
            assertThat(concurrently(tasks)).as("round " + round).containsOnly("200");
            awaitSettled(pending);
            for (Placed o : toCancel) assertThat(entries(o.orderNumber(), "SALE_REVERSAL")).isEqualTo(1);
            for (Placed o : toAck) assertThat(entries(o.orderNumber(), "REFUND")).isEqualTo(1);
            assertNoDrift(before, "round " + round + " after mixed concurrent postings");
        }
    }

    // ------------------------------------------------- rework F2: refund ack with too little cash

    @Test
    void refundAckWithTooLittleCashIsA409InsufficientCashAndPostsNothing() throws Exception {
        Placed o = placePaid("lb-ic@acme.test", 2);
        BigDecimal total = total(o.orderNumber());
        assertThat(status(setStatus(o.orderNumber(), "CANCELLED"))).isEqualTo(200);
        BigDecimal cash = accountBalance("CASH");
        BigDecimal low = total.subtract(new BigDecimal("1.00"));
        jdbc.update("UPDATE tenant_acme.ledger_accounts SET balance = ? WHERE category = 'CASH'", low);
        try {
            MvcResult r = ack(o.orderNumber());
            assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(409);
            assertThat(code(r)).isEqualTo("INSUFFICIENT_CASH");
            assertThat(new BigDecimal(body(r).at("/data/currentBalance").asText())).isEqualByComparingTo(low);
            assertThat(new BigDecimal(body(r).at("/data/attemptedAmount").asText())).isEqualByComparingTo(total);
            assertThat(body(r).at("/data/orderNumber").asText()).isEqualTo(o.orderNumber());
            assertThat(accountBalance("CASH")).as("rolled back").isEqualByComparingTo(low);
            assertThat(entries(o.orderNumber(), "REFUND")).isZero();
            assertThat(jdbc.queryForObject("SELECT p.refund_required FROM tenant_acme.payments p JOIN tenant_acme.orders o ON o.id = p.order_id "
                    + "WHERE o.order_number = ?", Boolean.class, o.orderNumber())).as("the refund stays required").isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.order_audit_events e JOIN tenant_acme.orders o ON o.id = e.order_id "
                    + "WHERE o.order_number = ? AND e.event_type = 'REFUND_ACKNOWLEDGED'", Integer.class, o.orderNumber())).isZero();
            // exactly enough cash: the refund goes through
            jdbc.update("UPDATE tenant_acme.ledger_accounts SET balance = ? WHERE category = 'CASH'", total);
            assertThat(status(ack(o.orderNumber()))).isEqualTo(200);
            assertThat(accountBalance("CASH")).isEqualByComparingTo("0");
            assertThat(entries(o.orderNumber(), "REFUND")).isEqualTo(1);
        } finally {
            jdbc.update("UPDATE tenant_acme.ledger_accounts SET balance = ? WHERE category = 'CASH'", cash);
        }
    }

    // ------------------------------------------- rework F3/F4/F5: every report exclusion, NULL delivery status

    @Test
    void paidCancelOfACouponOrderDropsOutOfEveryReportAndKeepsNullDeliveryStatusRows() throws Exception {
        coupon(acme, "ORD02RW", 10);
        long p = costedProduct("Coupon rework", 20);
        Customer c = customer(acme, "lb-cp@acme.test");
        Placed kept = settle(placeAs(c, "couponCode=ORD02RW", 2, p));
        BigDecimal keptTotal = total(kept.orderNumber());
        Reports only = reports();
        // the kept order shows in every report (so an exclusion that drops everything is caught too)
        assertThat(only.totalRevenue()).isEqualByComparingTo(keptTotal);
        assertThat(only.dailyRevenue()).isEqualByComparingTo(keptTotal);
        assertThat(only.couponListUsages()).isEqualTo(1);
        assertThat(only.couponUsages()).isEqualTo(1);
        assertThat(only.couponRevenue()).isEqualByComparingTo(keptTotal);
        assertThat(only.topCustomers()).containsOnlyKeys(c.email());
        assertThat(only.topCustomers().get(c.email())).startsWith("1 / ");
        assertThat(only.analyticsCogs()).isEqualByComparingTo("8.00");
        assertThat(only.analyticsExpenses()).isEqualByComparingTo("2.00");
        assertThat(only.soldByReference()).containsOnlyKeys(kept.orderNumber());
        String spentOnly = detailSpent(c);
        String listedOnly = listedSpent(c);

        Placed cancelled = settle(placeAs(c, "couponCode=ORD02RW", 3, p));
        Reports both = reports();
        assertThat(both.couponUsages()).isEqualTo(2);
        assertThat(both.analyticsCogs()).isEqualByComparingTo("20.00");
        assertThat(both.analyticsExpenses()).isEqualByComparingTo("5.00");
        assertThat(both.soldByReference()).containsOnlyKeys(kept.orderNumber(), cancelled.orderNumber());
        assertThat(detailSpent(c)).isNotEqualTo(spentOnly);

        assertThat(status(setStatus(cancelled.orderNumber(), "CANCELLED"))).isEqualTo(200);
        assertSameReports(reports(), only, "after the paid cancel");
        assertThat(detailSpent(c))
                .as("customer details exclude the cancelled paid order").isEqualTo(spentOnly);
        assertThat(listedSpent(c)).as("customer list excludes the cancelled paid order").isEqualTo(listedOnly);

        // F4: a paid order whose delivery status is NULL (legacy row) is NOT a cancelled order and stays in every report
        jdbc.update("UPDATE tenant_acme.orders SET delivery_status = NULL WHERE order_number = ?", kept.orderNumber());
        assertSameReports(reports(), only, "with a NULL delivery status");
        assertThat(listedSpent(c)).as("customer list keeps a NULL delivery status").isEqualTo(listedOnly);
        assertThat(detailSpent(c)).as("customer details keep a NULL delivery status").isEqualTo(spentOnly);

        // F5: an unpaid order is not money spent (customer list and details agree with top customers)
        placeAs(c, null, 1, p);
        assertThat(listedSpent(c)).as("customer list ignores an unpaid order").isEqualTo(listedOnly);
        assertThat(detailSpent(c)).as("customer details ignore an unpaid order").isEqualTo(spentOnly);
    }

    @Test
    void theSharedTestResetCopesWithAppendOnlyAuditRows() throws Exception {
        Placed o = placePaid("lb-reset@acme.test", 1);
        assertThat(status(setStatus(o.orderNumber(), "CANCELLED"))).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.order_audit_events", Integer.class)).isPositive();
        awaitBackgroundIdle();
        resetOrders("tenant_acme"); // the order part of the AbstractTenantWebTest @AfterEach: must not trip over the RESTRICT FK / DELETE trigger
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.orders", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.order_audit_events", Integer.class)).isZero();
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
    void ord02b_paidCancelGivesTheCouponRedemptionBackExactlyOnceUnderConcurrentCancels() throws Exception {
        coupon(acme, "ORD02B", 10);
        jdbc.update("UPDATE tenant_acme.coupons SET usage_limit = 2, per_user_usage_limit = 1 WHERE code = 'ORD02B'");
        long p = costedProduct("Coupon give-back", 20);
        Customer c = customer(acme, "cb-1@acme.test");
        Placed kept = settle(placeAs(customer(acme, "cb-2@acme.test"), "couponCode=ORD02B", 1, p)); // another live redemption
        Placed o = settle(placeAs(c, "couponCode=ORD02B", 2, p));
        assertThat(couponRow("ORD02B")).containsEntry("usage_count", 2).containsEntry("is_active", false)
                .containsEntry("limit_deactivated", true);
        // per-user limit reached for c while the order holds the redemption
        cart(c, p, 1);
        MvcResult refused = checkout(c, null, "couponCode=ORD02B");
        assertThat(status(refused)).as(refused.getResponse().getContentAsString()).isEqualTo(400);
        jdbc.update("DELETE FROM tenant_acme.cart_items WHERE cart_id IN (SELECT id FROM tenant_acme.carts WHERE user_id = ?)", c.id());

        Map<String, BigDecimal> before = drift();
        assertThat(concurrently(List.of(() -> outcome(setStatus(o.orderNumber(), "CANCELLED")),
                () -> outcome(setStatus(o.orderNumber(), "CANCELLED")), () -> outcome(setStatus(o.orderNumber(), "CANCELLED")))))
                .containsOnly("200");
        assertThat(status(setStatus(o.orderNumber(), "CANCELLED"))).as("retry").isEqualTo(200);

        assertThat(released(o.orderNumber())).isTrue();
        assertThat(released(kept.orderNumber())).isFalse();
        // exactly one release: the other customer's redemption is still counted (a double release would show 0)
        assertThat(couponRow("ORD02B")).containsEntry("usage_count", 1).containsEntry("is_active", true)
                .containsEntry("limit_deactivated", false);
        assertThat(entries(o.orderNumber(), "SALE_REVERSAL")).isEqualTo(1);
        assertThat(net(o.orderNumber(), "DISCOUNT_EXPENSE")).as("the coupon discount is reversed with the sale").isEqualByComparingTo("0");
        assertNoDrift(before, "paid cancel with a coupon");
        assertJournalBalanced("paid cancel with a coupon");

        // the per-user limit no longer counts the cancelled order: c can redeem the coupon again
        Placed again = placeAs(c, "couponCode=ORD02B", 1, p);
        assertThat(couponRow("ORD02B")).containsEntry("usage_count", 2);
        assertThat(released(again.orderNumber())).isFalse();
    }

    private void runV16Backfill() throws Exception {
        String sql = new String(getClass().getResourceAsStream("/db/migrations/tenant/V16__paid_cancel_coupon_release.sql").readAllBytes());
        String stmt = sql.substring(sql.indexOf("WITH released AS"), sql.indexOf(";", sql.indexOf("WITH released AS")));
        jdbc.execute(stmt.replace("UPDATE orders", "UPDATE tenant_acme.orders").replace("UPDATE coupons", "UPDATE tenant_acme.coupons"));
    }

    @Test
    void ord02b_v16ReleasesTheRedemptionOfPaidOrdersCancelledBeforeThisReleaseOnce() throws Exception {
        coupon(acme, "V16", 10);
        jdbc.update("UPDATE tenant_acme.coupons SET usage_limit = 4 WHERE code = 'V16'");
        long p = costedProduct("V16 backfill", 20);
        Placed live = settle(placeAs(customer(acme, "v16-1@acme.test"), "couponCode=V16", 1, p));
        Placed legacy = settle(placeAs(customer(acme, "v16-2@acme.test"), "couponCode=V16", 1, p));
        // ORD-02c: paid coupon orders that are DELIVERED or still in progress (PACKING) are not cancelled: V16 keeps
        // their redemptions (a V16 that also matched DELIVERED, or any paid order, would release them)
        Placed delivered = settle(placeAs(customer(acme, "v16-4@acme.test"), "couponCode=V16", 1, p));
        assertThat(status(setStatus(delivered.orderNumber(), "DELIVERED"))).isEqualTo(200);
        Placed packing = settle(placeAs(customer(acme, "v16-5@acme.test"), "couponCode=V16", 1, p));
        assertThat(status(setStatus(packing.orderNumber(), "PACKING"))).isEqualTo(200);
        assertThat(jdbc.queryForList("SELECT status || '/' || delivery_status FROM tenant_acme.orders WHERE order_number IN (?, ?) "
                + "ORDER BY delivery_status", String.class, delivered.orderNumber(), packing.orderNumber()))
                .containsExactly("COMPLETED/DELIVERED", "COMPLETED/PACKING");
        assertThat(status(setStatus(legacy.orderNumber(), "CANCELLED"))).isEqualTo(200);
        // what a paid cancel left behind before ORD-02b: the redemption still held, the coupon switched off at its limit
        jdbc.update("UPDATE tenant_acme.orders SET coupon_redemption_released = false WHERE order_number = ?", legacy.orderNumber());
        jdbc.update("UPDATE tenant_acme.coupons SET usage_count = 4, is_active = false, limit_deactivated = true WHERE code = 'V16'");
        // an unpaid cancelled order (released at its cancel) and a paid order with a NULL delivery status stay as they are
        Placed unpaid = placeAs(customer(acme, "v16-3@acme.test"), null, 1, p);
        assertThat(status(setStatus(unpaid.orderNumber(), "CANCELLED"))).isEqualTo(200);

        runV16Backfill();
        assertThat(released(legacy.orderNumber())).isTrue();
        assertThat(released(live.orderNumber())).isFalse();
        assertThat(released(delivered.orderNumber())).as("DELIVERED keeps its redemption").isFalse();
        assertThat(released(packing.orderNumber())).as("PACKING keeps its redemption").isFalse();
        assertThat(couponRow("V16")).as("only the legacy paid cancel released")
                .containsEntry("usage_count", 3).containsEntry("is_active", true).containsEntry("limit_deactivated", false);
        runV16Backfill();
        assertThat(couponRow("V16")).as("idempotent").containsEntry("usage_count", 3);
        assertThat(released(delivered.orderNumber())).isFalse();
        assertThat(released(packing.orderNumber())).isFalse();

        // a coupon an admin switched off (not limit-deactivated) is never switched back on
        jdbc.update("UPDATE tenant_acme.orders SET coupon_redemption_released = false WHERE order_number = ?", legacy.orderNumber());
        jdbc.update("UPDATE tenant_acme.coupons SET usage_count = 4, is_active = false, limit_deactivated = false WHERE code = 'V16'");
        jdbc.update("UPDATE tenant_acme.orders SET delivery_status = NULL WHERE order_number = ?", live.orderNumber());
        runV16Backfill();
        assertThat(couponRow("V16")).containsEntry("usage_count", 3).containsEntry("is_active", false);
        assertThat(released(live.orderNumber())).isFalse();
        assertThat(released(delivered.orderNumber())).isFalse();
        assertThat(released(packing.orderNumber())).isFalse();
    }

    /**
     * ORD-02c (from the ORD-02b reviewer's probe G): V16 through the real Flyway run on a realistic mix of orders
     * releases only the legacy paid cancel, once; delivered, in-progress and still-pending coupon orders keep theirs.
     */
    @Test
    void ord02c_v16ThroughRealFlywayReleasesOnlyLegacyPaidCancelsOnce() throws Exception {
        Gateway g = configureGateway(acme);
        coupon(acme, "PRG", 10);
        jdbc.update("UPDATE tenant_acme.coupons SET usage_limit = 50 WHERE code = 'PRG'");
        long p = costedProduct("PRG prod", 200);
        Placed newCancel = settle(placeAs(customer(acme, "prg-1@acme.test"), "couponCode=PRG", 1, p));
        assertThat(status(setStatus(newCancel.orderNumber(), "CANCELLED"))).isEqualTo(200); // released by the app
        Placed legacy = settle(placeAs(customer(acme, "prg-2@acme.test"), "couponCode=PRG", 1, p));
        assertThat(status(setStatus(legacy.orderNumber(), "CANCELLED"))).isEqualTo(200);
        jdbc.update("UPDATE tenant_acme.orders SET coupon_redemption_released = false WHERE order_number = ?", legacy.orderNumber());
        jdbc.update("UPDATE tenant_acme.coupons SET usage_count = usage_count + 1 WHERE code = 'PRG'"); // legacy state
        Placed delivered = settle(placeAs(customer(acme, "prg-3@acme.test"), "couponCode=PRG", 1, p));
        assertThat(status(setStatus(delivered.orderNumber(), "DELIVERED"))).isEqualTo(200);
        Placed packing = settle(placeAs(customer(acme, "prg-4@acme.test"), "couponCode=PRG", 1, p));
        assertThat(status(setStatus(packing.orderNumber(), "PACKING"))).isEqualTo(200);
        Placed unpaidCancel = placeAs(customer(acme, "prg-5@acme.test"), "couponCode=PRG", 1, p);
        assertThat(status(setStatus(unpaidCancel.orderNumber(), "CANCELLED"))).isEqualTo(200);
        Placed latePaid = placeAs(customer(acme, "prg-6@acme.test"), "couponCode=PRG", 1, p);
        assertThat(status(setStatus(latePaid.orderNumber(), "CANCELLED"))).isEqualTo(200);
        assertThat(webhook(g, event("charge.success", latePaid.reference(), minor(latePaid.orderNumber())))).isEqualTo(200);
        Placed noCoupon = placePaid("prg-7@acme.test", 1, p);
        assertThat(status(setStatus(noCoupon.orderNumber(), "CANCELLED"))).isEqualTo(200);
        Placed livePending = placeAs(customer(acme, "prg-8@acme.test"), "couponCode=PRG", 1, p);
        awaitBackgroundIdle();
        // held: delivered, packing, livePending, legacy = 4
        assertThat(couponRow("PRG")).containsEntry("usage_count", 4);

        jdbc.update("DELETE FROM tenant_acme.flyway_schema_history WHERE version IS NOT NULL AND CAST(version AS INTEGER) >= 16");
        migrator.migrateTenant("tenant_acme");
        assertThat(jdbc.queryForObject("SELECT success FROM tenant_acme.flyway_schema_history WHERE version = '16'", Boolean.class)).isTrue();
        assertThat(couponRow("PRG")).as("only the legacy paid cancel released").containsEntry("usage_count", 3);
        assertThat(released(legacy.orderNumber())).isTrue();
        assertThat(released(delivered.orderNumber())).isFalse();
        assertThat(released(packing.orderNumber())).isFalse();
        assertThat(released(livePending.orderNumber())).isFalse();
        migrator.migrateTenant("tenant_acme"); // no-op
        jdbc.update("DELETE FROM tenant_acme.flyway_schema_history WHERE version IS NOT NULL AND CAST(version AS INTEGER) >= 16");
        migrator.migrateTenant("tenant_acme"); // re-run the data fix
        assertThat(couponRow("PRG")).as("idempotent re-run").containsEntry("usage_count", 3);
        assertThat(released(delivered.orderNumber())).isFalse();
        assertThat(released(packing.orderNumber())).isFalse();
        // a paid cancel after the migration still releases once; a DELIVERED order cannot be cancelled
        assertThat(status(setStatus(delivered.orderNumber(), "CANCELLED"))).isEqualTo(400);
        assertThat(status(setStatus(packing.orderNumber(), "CANCELLED"))).isEqualTo(200);
        assertThat(couponRow("PRG")).containsEntry("usage_count", 2);
    }

    @Test
    void ord02b_latePaymentOnAMerchantCancelledOrderIsBookedOnceAndItsRefundAckClearsIt() throws Exception {
        Gateway g = configureGateway(acme);
        Placed o = place("lp-1@acme.test", 2);
        BigDecimal total = total(o.orderNumber());
        assertThat(status(setStatus(o.orderNumber(), "CANCELLED"))).isEqualTo(200);
        BigDecimal cashBefore = accountBalance("CASH");
        BigDecimal owedBefore = accountBalance("REFUND_PAYABLE");
        Map<String, BigDecimal> before = drift();

        // Paystack redelivers: three simultaneous deliveries, then a late one
        assertThat(duplicateDeliveries(g, o, 3)).containsOnly(200);
        assertThat(webhook(g, event("charge.success", o.reference(), minor(o.orderNumber())))).isEqualTo(200);
        awaitBackgroundIdle();

        assertThat(refundRequired(o.orderNumber())).isTrue();
        assertThat(orderStatus(acme, o.orderNumber())).isEqualTo("CANCELLED");
        assertThat(entries(o.orderNumber(), "LATE_PAYMENT")).as("booked exactly once").isEqualTo(1);
        assertThat(entries(o.orderNumber(), "SALE")).as("no sale").isZero();
        assertThat(net(o.orderNumber(), "SALES_REVENUE")).isEqualByComparingTo("0");
        assertThat(net(o.orderNumber(), "CASH")).as("cash debited").isEqualByComparingTo(total.negate());
        assertThat(net(o.orderNumber(), "REFUND_PAYABLE")).as("refund owed credited").isEqualByComparingTo(total);
        assertThat(accountBalance("CASH")).isEqualByComparingTo(cashBefore.add(total));
        assertThat(accountBalance("REFUND_PAYABLE")).isEqualByComparingTo(owedBefore.add(total));
        assertNoDrift(before, "late payment booked");
        assertJournalBalanced("late payment booked");

        assertThat(status(ack(o.orderNumber()))).isEqualTo(200);
        assertThat(entries(o.orderNumber(), "REFUND")).isEqualTo(1);
        assertThat(net(o.orderNumber(), "CASH")).isEqualByComparingTo("0");
        assertThat(net(o.orderNumber(), "REFUND_PAYABLE")).isEqualByComparingTo("0");
        assertThat(accountBalance("CASH")).isEqualByComparingTo(cashBefore);
        assertThat(accountBalance("REFUND_PAYABLE")).isEqualByComparingTo(owedBefore);
        assertThat(code(ack(o.orderNumber()))).isEqualTo("REFUND_NOT_REQUIRED");
        assertThat(webhook(g, event("charge.success", o.reference(), minor(o.orderNumber())))).as("redelivery after the ack").isEqualTo(200);
        awaitBackgroundIdle();
        assertThat(entries(o.orderNumber(), "LATE_PAYMENT")).isEqualTo(1);
        assertThat(entries(o.orderNumber(), "REFUND")).isEqualTo(1);
        assertNoDrift(before, "late payment refunded");
        assertJournalBalanced("late payment refunded");
    }

    private BigDecimal refundPaid(String orderNumber) {
        return jdbc.queryForObject("SELECT coalesce(sum(l.amount), 0) FROM tenant_acme.journal_entry_lines l "
                + "JOIN tenant_acme.journal_entries j ON j.id = l.journal_entry_id JOIN tenant_acme.ledger_accounts a ON a.id = l.account_id "
                + "WHERE j.reference_id = ? AND j.type = 'REFUND' AND a.category = 'REFUND_PAYABLE' AND l.entry_type = 'DEBIT'",
                BigDecimal.class, orderNumber);
    }

    /**
     * ORD-02c (ORD-02b review low 4): the LATE_PAYMENT date, the posting's own once-per-order guard and the REFUND
     * amount (what is still owed = Refunds Payable credits minus earlier REFUNDs) against the real app + Postgres.
     */
    @Test
    void ord02c_latePaymentIsDatedAtThePaymentGuardedOnceAndItsRefundPaysOnlyWhatIsStillOwed() throws Exception {
        Gateway g = configureGateway(acme);
        Placed o = place("lp-3@acme.test", 2);
        BigDecimal total = total(o.orderNumber());
        assertThat(status(setStatus(o.orderNumber(), "CANCELLED"))).isEqualTo(200);
        BigDecimal cashBefore = accountBalance("CASH");
        Map<String, BigDecimal> before = drift();
        assertThat(webhook(g, event("charge.success", o.reference(), minor(o.orderNumber())))).isEqualTo(200);
        awaitBackgroundIdle();

        // dated at the payment (paidAt, microsecond-exact), not at a later clock read
        Map<String, Object> dates = jdbc.queryForMap("SELECT p.paid_at, j.transaction_date FROM tenant_acme.payments p "
                + "JOIN tenant_acme.orders o ON o.id = p.order_id JOIN tenant_acme.journal_entries j ON j.reference_id = o.order_number "
                + "AND j.type = 'LATE_PAYMENT' WHERE o.order_number = ?", o.orderNumber());
        assertThat(dates.get("paid_at")).isNotNull();
        assertThat(dates.get("transaction_date")).as("LATE_PAYMENT transaction_date = paidAt").isEqualTo(dates.get("paid_at"));
        assertThat(entries(o.orderNumber(), "LATE_PAYMENT")).isEqualTo(1);

        // the posting's own guard: a settlement attempt whose COMPLETED write was lost (payment back to its
        // pre-settlement state) runs the late-payment path again and must not book a second LATE_PAYMENT
        jdbc.update("UPDATE tenant_acme.payments SET status = 'CANCELLED' WHERE order_id = "
                + "(SELECT id FROM tenant_acme.orders WHERE order_number = ?)", o.orderNumber());
        assertThat(webhook(g, event("charge.success", o.reference(), minor(o.orderNumber())))).isEqualTo(200);
        awaitBackgroundIdle();
        assertThat(jdbc.queryForObject("SELECT p.status FROM tenant_acme.payments p JOIN tenant_acme.orders o ON o.id = p.order_id "
                + "WHERE o.order_number = ?", String.class, o.orderNumber())).as("settled again").isEqualTo("COMPLETED");
        assertThat(entries(o.orderNumber(), "LATE_PAYMENT")).as("guarded: still one").isEqualTo(1);
        assertThat(accountBalance("CASH")).isEqualByComparingTo(cashBefore.add(total));
        assertThat(net(o.orderNumber(), "REFUND_PAYABLE")).isEqualByComparingTo(total);
        assertNoDrift(before, "late payment re-settled");

        // the ack pays out exactly what is owed; a second ack is 409 REFUND_NOT_REQUIRED and posts nothing
        assertThat(status(ack(o.orderNumber()))).isEqualTo(200);
        assertThat(refundPaid(o.orderNumber())).as("REFUND = owed").isEqualByComparingTo(total);
        MvcResult again = ack(o.orderNumber());
        assertThat(status(again)).isEqualTo(409);
        assertThat(code(again)).isEqualTo("REFUND_NOT_REQUIRED");
        assertThat(entries(o.orderNumber(), "REFUND")).isEqualTo(1);

        // the REFUND subtracts earlier REFUNDs: if the refund flag is raised again with nothing left owed, the ack posts
        // nothing (without the subtraction it would pay the whole amount out a second time)
        jdbc.update("UPDATE tenant_acme.payments SET refund_required = true WHERE order_id = "
                + "(SELECT id FROM tenant_acme.orders WHERE order_number = ?)", o.orderNumber());
        assertThat(status(ack(o.orderNumber()))).isEqualTo(200);
        assertThat(entries(o.orderNumber(), "REFUND")).isEqualTo(1);
        assertThat(refundPaid(o.orderNumber())).isEqualByComparingTo(total);
        assertThat(net(o.orderNumber(), "CASH")).isEqualByComparingTo("0");
        assertThat(net(o.orderNumber(), "REFUND_PAYABLE")).isEqualByComparingTo("0");
        assertThat(accountBalance("CASH")).isEqualByComparingTo(cashBefore);
        assertNoDrift(before, "late payment refunded once");
        assertJournalBalanced("late payment refunded once");
    }

    /** ORD-02c (review low 3, documented in orders.md): how an admin finds and resolves a late payment on a FAILED order. */
    @Test
    void ord02c_latePaymentOnAFailedOrderIsFoundByTheDocumentedListFilterAndResolvedByCancelThenAck() throws Exception {
        Gateway g = configureGateway(acme);
        Placed o = place("lp-4@acme.test", 2);
        BigDecimal total = total(o.orderNumber());
        assertThat(webhook(g, event("charge.failed", o.reference(), minor(o.orderNumber())))).isEqualTo(200);
        awaitBackgroundIdle();
        assertThat(orderStatus(acme, o.orderNumber())).isEqualTo("FAILED");
        Placed other = place("lp-5@acme.test", 1); // an ordinary open order: must not match the filter
        jdbc.update("UPDATE tenant_acme.inventory_layers SET remaining_quantity = 0 WHERE product_id = ?", o.products().get(0));
        assertThat(webhook(g, event("charge.success", o.reference(), minor(o.orderNumber())))).isEqualTo(200);
        awaitBackgroundIdle();

        MvcResult list = mvc.perform(admin(get("/api/v1/order/all?paymentStatus=FAILED&orderStatus=PENDING&size=50"))).andReturn();
        assertThat(status(list)).as(list.getResponse().getContentAsString()).isEqualTo(200);
        List<String> flagged = new ArrayList<>();
        for (JsonNode row : body(list).at("/data/content")) {
            assertThat(row.get("paymentStatus").asText()).isEqualTo("FAILED");
            assertThat(row.get("deliveryStatus").asText()).isEqualTo("PENDING");
            if (row.get("refundRequired").asBoolean()) flagged.add(row.get("orderNumber").asText());
        }
        assertThat(flagged).containsExactly(o.orderNumber());
        assertThat(flagged).doesNotContain(other.orderNumber());
        assertThat(entries(o.orderNumber(), "LATE_PAYMENT")).isEqualTo(1);

        MvcResult cancelled = setStatus(o.orderNumber(), "CANCELLED");
        assertThat(status(cancelled)).isEqualTo(200);
        assertThat(body(cancelled).at("/data/refundRequired").asBoolean()).isTrue();
        assertThat(entries(o.orderNumber(), "SALE_REVERSAL")).as("no sale to reverse").isZero();
        assertThat(status(ack(o.orderNumber()))).isEqualTo(200);
        assertThat(refundPaid(o.orderNumber())).isEqualByComparingTo(total);
        assertThat(net(o.orderNumber(), "REFUND_PAYABLE")).isEqualByComparingTo("0");
        MvcResult after = mvc.perform(admin(get("/api/v1/order/all?paymentStatus=FAILED&orderStatus=PENDING&size=50"))).andReturn();
        for (JsonNode row : body(after).at("/data/content")) {
            assertThat(row.get("orderNumber").asText()).isNotEqualTo(o.orderNumber());
        }
        assertJournalBalanced("late payment on a failed order resolved");
    }

    @Test
    void ord02b_latePaymentWithoutStockIsBookedOnceAndItsAckHonoursInsufficientCash() throws Exception {
        Gateway g = configureGateway(acme);
        Placed o = place("lp-2@acme.test", 6);
        BigDecimal total = total(o.orderNumber());
        assertThat(status(cancel(o.c(), o.orderNumber()))).isEqualTo(200);
        // someone else takes the stock meanwhile: the late payment cannot revive the order
        jdbc.update("UPDATE tenant_acme.inventory_layers SET remaining_quantity = 0 WHERE product_id = ?", o.products().get(0));
        Map<String, BigDecimal> before = drift();

        assertThat(duplicateDeliveries(g, o, 3)).containsOnly(200);
        awaitBackgroundIdle();
        assertThat(refundRequired(o.orderNumber())).isTrue();
        assertThat(orderStatus(acme, o.orderNumber())).isEqualTo("CANCELLED");
        assertThat(entries(o.orderNumber(), "LATE_PAYMENT")).isEqualTo(1);
        assertThat(entries(o.orderNumber(), "SALE")).isZero();
        assertThat(net(o.orderNumber(), "REFUND_PAYABLE")).isEqualByComparingTo(total);
        assertNoDrift(before, "late payment without stock");
        assertJournalBalanced("late payment without stock");

        BigDecimal cash = accountBalance("CASH");
        BigDecimal low = total.subtract(new BigDecimal("0.01"));
        jdbc.update("UPDATE tenant_acme.ledger_accounts SET balance = ? WHERE category = 'CASH'", low);
        try {
            MvcResult r = ack(o.orderNumber());
            assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(409);
            assertThat(code(r)).isEqualTo("INSUFFICIENT_CASH");
            assertThat(new BigDecimal(body(r).at("/data/attemptedAmount").asText())).isEqualByComparingTo(total);
            assertThat(new BigDecimal(body(r).at("/data/currentBalance").asText())).isEqualByComparingTo(low);
            assertThat(accountBalance("CASH")).isEqualByComparingTo(low);
            assertThat(entries(o.orderNumber(), "REFUND")).isZero();
            assertThat(refundRequired(o.orderNumber())).isTrue();
        } finally {
            jdbc.update("UPDATE tenant_acme.ledger_accounts SET balance = ? WHERE category = 'CASH'", cash);
        }
        assertThat(status(ack(o.orderNumber()))).isEqualTo(200);
        assertThat(entries(o.orderNumber(), "REFUND")).isEqualTo(1);
        assertThat(net(o.orderNumber(), "REFUND_PAYABLE")).isEqualByComparingTo("0");
        assertThat(net(o.orderNumber(), "CASH")).isEqualByComparingTo("0");
        assertNoDrift(before, "late payment without stock refunded");
        assertJournalBalanced("late payment without stock refunded");
    }

    @Test
    void ord02b_aDeliveredOrderIsNotReturnableOrCancellableToday() throws Exception {
        Placed o = placePaid("dl-1@acme.test", 1);
        assertThat(status(setStatus(o.orderNumber(), "DELIVERED"))).isEqualTo(200);
        MvcResult cancel = setStatus(o.orderNumber(), "CANCELLED");
        assertThat(status(cancel)).isEqualTo(400);
        assertThat(code(cancel)).isEqualTo("ORDER_FINAL_STATE");
        MvcResult ret = returnReceived(o.orderNumber());
        assertThat(status(ret)).isEqualTo(409);
        assertThat(code(ret)).isEqualTo("RETURN_NOT_APPLICABLE");
        assertThat(body(ret).at("/data/deliveryStatus").asText()).isEqualTo("DELIVERED");
        assertThat(body(ret).at("/data/paymentStatus").asText()).isEqualTo("COMPLETED");
        MvcResult refund = ack(o.orderNumber());
        assertThat(status(refund)).isEqualTo(409);
        assertThat(code(refund)).isEqualTo("REFUND_NOT_REQUIRED");
        MvcResult own = cancel(o.c(), o.orderNumber());
        assertThat(status(own)).isEqualTo(409);
        assertThat(code(own)).isEqualTo("ORDER_NOT_CANCELLABLE");
        assertThat(entries(o.orderNumber(), "SALE")).isEqualTo(1);
        assertThat(entries(o.orderNumber(), "SALE_REVERSAL")).isZero();
    }

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
}
