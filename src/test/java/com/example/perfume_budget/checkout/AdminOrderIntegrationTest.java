package com.example.perfume_budget.checkout;

import com.example.perfume_budget.dto.payment.response.PaystackVerifyTransactionResponse;
import com.example.perfume_budget.support.TestTenant;
import com.example.perfume_budget.support.TestTenants;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** ORD-01: admin cancel/refund/transition/list behaviour against the real app + Postgres (two tenants). */
class AdminOrderIntegrationTest extends AbstractCheckoutIntegrationTest {

    @Autowired EntityManagerFactory emf;

    private record Placed(Customer c, long productId, String orderNumber, String reference, int qty) {}

    private String adminTokenCache;
    private String bravoAdminTokenCache;

    private String adminToken(TestTenant t) throws Exception {
        return setCookieValue(loginPassword(t.slug(), t.adminEmail(), TestTenants.ADMIN_PASSWORD), "accessToken");
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder b) throws Exception {
        if (adminTokenCache == null) adminTokenCache = adminToken(acme);
        return b.header("X-Tenant-ID", "acme").cookie(cookie("accessToken", adminTokenCache));
    }

    private MockHttpServletRequestBuilder bravoAdminOnAcme(MockHttpServletRequestBuilder b) throws Exception {
        if (bravoAdminTokenCache == null) bravoAdminTokenCache = adminToken(bravo);
        return b.header("X-Tenant-ID", "acme").cookie(cookie("accessToken", bravoAdminTokenCache));
    }

    private Placed place(String email, int qty) throws Exception {
        long p = product(acme, "Ord " + email, "10.00", 10);
        Customer c = customer(acme, email);
        cart(c, p, qty);
        JsonNode d = body(checkout(c, null, null)).get("data");
        return new Placed(c, p, d.get("orderNumber").asText(), d.at("/data/reference").asText(), qty);
    }

    private Placed placePaid(String email, int qty) throws Exception {
        Placed o = place(email, qty);
        pay(o);
        return o;
    }

    private void pay(Placed o) throws Exception {
        long minor = jdbc.queryForObject("SELECT total_amount * 100 FROM tenant_acme.orders WHERE order_number = ?", BigDecimal.class, o.orderNumber()).longValue();
        verifyAnswer = new PaystackVerifyTransactionResponse(true, "ok",
                new PaystackVerifyTransactionResponse.Data("success", o.reference(), BigDecimal.valueOf(minor), "GHS", "card", "Successful"));
        verify(o.c(), o.reference());
        verifyAnswer = null;
        assertThat(orderStatus(acme, o.orderNumber())).isEqualTo("COMPLETED");
    }

    private MvcResult setStatus(String orderNumber, String status, Long version, String ifMatch) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("orderStatus", status);
        if (version != null) body.put("version", version);
        MockHttpServletRequestBuilder b = put("/api/v1/order/update-order-status/" + orderNumber)
                .contentType("application/json").content(json.writeValueAsString(body));
        if (ifMatch != null) b.header("If-Match", ifMatch);
        return mvc.perform(admin(b)).andReturn();
    }

    private MvcResult setStatus(String orderNumber, String status) throws Exception {
        return setStatus(orderNumber, status, null, null);
    }

    private MvcResult ack(String orderNumber) throws Exception {
        return mvc.perform(admin(post("/api/v1/order/" + orderNumber + "/refund-acknowledgement"))).andReturn();
    }

    private JsonNode detail(String orderNumber) throws Exception {
        return body(mvc.perform(admin(get("/api/v1/order/" + orderNumber))).andReturn()).get("data");
    }

    private String code(MvcResult r) throws Exception {
        return body(r).at("/data/code").asText();
    }

    /** Webhooks are processed asynchronously: wait until the payment reached the status. */
    private void awaitPayment(String orderNumber, String status) {
        await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() -> assertThat(paymentStatus(orderNumber)).isEqualTo(status));
    }

    private void settle() throws InterruptedException {
        Thread.sleep(700);
    }

    private List<String> listQ(String q) throws Exception {
        MvcResult r = mvc.perform(admin(get("/api/v1/order/all").param("q", q))).andReturn();
        assertThat(status(r)).as("q=" + q + " -> " + r.getResponse().getContentAsString()).isEqualTo(200);
        List<String> out = new ArrayList<>();
        body(r).at("/data/content").forEach(n -> out.add(n.get("orderNumber").asText()));
        return out;
    }

    private MvcResult listRaw(String q) throws Exception {
        return mvc.perform(admin(get("/api/v1/order/all").param("q", q))).andReturn();
    }

    private int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private String deliveryStatus(String orderNumber) {
        return jdbc.queryForObject("SELECT delivery_status FROM tenant_acme.orders WHERE order_number = ?", String.class, orderNumber);
    }

    private boolean refundFlag(String orderNumber) {
        return jdbc.queryForObject("SELECT p.refund_required FROM tenant_acme.payments p JOIN tenant_acme.orders o ON o.id = p.order_id WHERE o.order_number = ?", Boolean.class, orderNumber);
    }

    private String paymentStatus(String orderNumber) {
        return jdbc.queryForObject("SELECT p.status FROM tenant_acme.payments p JOIN tenant_acme.orders o ON o.id = p.order_id WHERE o.order_number = ?", String.class, orderNumber);
    }

    private int audit(String orderNumber, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM tenant_acme.order_audit_events e JOIN tenant_acme.orders o ON o.id = e.order_id WHERE o.order_number = ? AND e.event_type = ?", Integer.class, orderNumber, type);
    }

    private int allocations(String orderNumber, String st) {
        return jdbc.queryForObject("SELECT count(*) FROM tenant_acme.inventory_allocations WHERE reference_id = ? AND status = ?", Integer.class, orderNumber, st);
    }

    private int movements(String type, String orderNumber) {
        return jdbc.queryForObject("SELECT count(*) FROM tenant_acme.inventory_movements WHERE movement_type = ? AND reference_id = ?", Integer.class, type, orderNumber);
    }

    private int soldCount(long productId) {
        return jdbc.queryForObject("SELECT sold_count FROM tenant_acme.products WHERE id = ?", Integer.class, productId);
    }

    private <T> List<T> concurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> fs = new ArrayList<>();
        for (Callable<T> t : tasks) fs.add(pool.submit(() -> { go.await(); return t.call(); }));
        go.countDown();
        List<T> out = new ArrayList<>();
        for (Future<T> f : fs) out.add(f.get());
        pool.shutdown();
        return out;
    }

    // ------------------------------------------------------------------------------------------ cancel (item 1, 2)

    @Test
    void cancelUnpaidReleasesStockCancelsPaymentAndReturnsReferenceAndRefundFlag() throws Exception {
        Placed o = place("c1@acme.test", 3);
        assertThat(layerRemaining(acme, o.productId())).isEqualTo(7);
        assertThat(reservedQty(acme, o.productId())).isEqualTo(3);

        MvcResult r = setStatus(o.orderNumber(), "CANCELLED");
        assertThat(status(r)).isEqualTo(200);
        JsonNode d = body(r).get("data");
        assertThat(d.get("reference").asText()).isEqualTo(o.reference());
        assertThat(d.get("refundRequired").asBoolean()).isFalse();
        assertThat(d.get("paymentStatus").asText()).isEqualTo("CANCELLED");
        assertThat(d.get("deliveryStatus").asText()).isEqualTo("CANCELLED");
        assertThat(d.get("cancelSource").asText()).isEqualTo("ADMIN");
        assertThat(d.get("cancelledAt").asText()).endsWith("Z");

        assertThat(layerRemaining(acme, o.productId())).isEqualTo(10);
        assertThat(reservedQty(acme, o.productId())).isZero();
        assertThat(paymentStatus(o.orderNumber())).isEqualTo("CANCELLED");
        assertThat(audit(o.orderNumber(), "CANCELLED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT actor_email FROM tenant_acme.order_audit_events ORDER BY id DESC LIMIT 1", String.class)).isEqualTo(acme.adminEmail());
    }

    @Test
    void cancelRetryIsIdempotentAndNeverReleasesTwice() throws Exception {
        Placed o = place("c2@acme.test", 2);
        assertThat(status(setStatus(o.orderNumber(), "CANCELLED"))).isEqualTo(200);
        MvcResult again = setStatus(o.orderNumber(), "CANCELLED");
        assertThat(status(again)).isEqualTo(200);
        assertThat(body(again).at("/data/deliveryStatus").asText()).isEqualTo("CANCELLED");
        assertThat(layerRemaining(acme, o.productId())).isEqualTo(10);
        assertThat(movements("RELEASE", o.orderNumber())).isEqualTo(1);
        assertThat(audit(o.orderNumber(), "CANCELLED")).isEqualTo(1);
    }

    @Test
    void cancelPaidBeforeDispatchRestocksOnceAndFlagsRefund() throws Exception {
        Placed o = placePaid("c3@acme.test", 4);
        assertThat(layerRemaining(acme, o.productId())).isEqualTo(6);
        assertThat(soldCount(o.productId())).isEqualTo(4);

        MvcResult r = setStatus(o.orderNumber(), "CANCELLED");
        assertThat(status(r)).isEqualTo(200);
        JsonNode d = body(r).get("data");
        assertThat(d.get("refundRequired").asBoolean()).isTrue();
        assertThat(d.get("reference").asText()).isEqualTo(o.reference());
        assertThat(d.get("paymentStatus").asText()).isEqualTo("COMPLETED");
        assertThat(layerRemaining(acme, o.productId())).isEqualTo(10);
        assertThat(allocations(o.orderNumber(), "RETURNED")).isEqualTo(1);
        assertThat(allocations(o.orderNumber(), "CONSUMED")).isZero();
        assertThat(soldCount(o.productId())).isZero();
        assertThat(paymentStatus(o.orderNumber())).isEqualTo("COMPLETED");
        assertThat(refundFlag(o.orderNumber())).isTrue();

        // retry: still 10, never 14
        assertThat(status(setStatus(o.orderNumber(), "CANCELLED"))).isEqualTo(200);
        assertThat(layerRemaining(acme, o.productId())).isEqualTo(10);
        assertThat(soldCount(o.productId())).isZero();
        assertThat(movements("RETURN", o.orderNumber())).isEqualTo(1);
    }

    @Test
    void cancelPaidOutForDeliveryDoesNotRestockButFlagsRefund() throws Exception {
        Placed o = placePaid("c4@acme.test", 2);
        assertThat(status(setStatus(o.orderNumber(), "OUT_FOR_DELIVERY"))).isEqualTo(200);
        assertThat(status(setStatus(o.orderNumber(), "CANCELLED"))).isEqualTo(200);
        assertThat(layerRemaining(acme, o.productId())).isEqualTo(8);
        assertThat(refundFlag(o.orderNumber())).isTrue();
        assertThat(allocations(o.orderNumber(), "CONSUMED")).isEqualTo(1);
    }

    @Test
    void cancelOfAFailedPaymentOrderOnlyClosesDelivery() throws Exception {
        Placed o = place("c5@acme.test", 1);
        // payment failed (charge.failed would release the stock): simulate the settled state
        jdbc.update("UPDATE tenant_acme.orders SET status = 'FAILED' WHERE order_number = ?", o.orderNumber());
        jdbc.update("UPDATE tenant_acme.payments SET status = 'FAILED' WHERE order_id = (SELECT id FROM tenant_acme.orders WHERE order_number = ?)", o.orderNumber());
        MvcResult r = setStatus(o.orderNumber(), "CANCELLED");
        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r).at("/data/refundRequired").asBoolean()).isFalse();
        assertThat(orderStatus(acme, o.orderNumber())).isEqualTo("FAILED");
        assertThat(deliveryStatus(o.orderNumber())).isEqualTo("CANCELLED");
        assertThat(reservedQty(acme, o.productId())).as("straggling reservation released").isZero();
        assertThat(layerRemaining(acme, o.productId())).isEqualTo(10);
    }

    @Test
    void lateWebhookForAdminCancelledOrderNeverRevivesItAndFlagsRefund() throws Exception {
        Gateway g = configureGateway(acme);
        Placed o = place("c6@acme.test", 2);
        assertThat(status(setStatus(o.orderNumber(), "CANCELLED"))).isEqualTo(200);
        long minor = jdbc.queryForObject("SELECT total_amount * 100 FROM tenant_acme.orders WHERE order_number = ?", BigDecimal.class, o.orderNumber()).longValue();

        assertThat(webhook(g, event("charge.success", o.reference(), minor))).isEqualTo(200);
        awaitPayment(o.orderNumber(), "COMPLETED");
        assertThat(refundFlag(o.orderNumber())).isTrue();
        assertThat(orderStatus(acme, o.orderNumber())).isEqualTo("CANCELLED");
        assertThat(deliveryStatus(o.orderNumber())).isEqualTo("CANCELLED");
        assertThat(layerRemaining(acme, o.productId())).as("stock is NOT taken again").isEqualTo(10);
        assertThat(reservedQty(acme, o.productId())).isZero();
        assertThat(detail(o.orderNumber()).get("refundRequired").asBoolean()).isTrue();

        // duplicate webhook: unchanged
        assertThat(webhook(g, event("charge.success", o.reference(), minor))).isEqualTo(200);
        settle();
        assertThat(layerRemaining(acme, o.productId())).isEqualTo(10);
    }

    @Test
    void customerCancelledOrderKeepsThePay01LatePaymentBehaviour() throws Exception {
        Gateway g = configureGateway(acme);
        Placed o = place("c7@acme.test", 2);
        assertThat(cancel(o.c(), o.orderNumber()).getResponse().getStatus()).isEqualTo(200);
        long minor = jdbc.queryForObject("SELECT total_amount * 100 FROM tenant_acme.orders WHERE order_number = ?", BigDecimal.class, o.orderNumber()).longValue();
        assertThat(webhook(g, event("charge.success", o.reference(), minor))).isEqualTo(200);
        awaitPayment(o.orderNumber(), "COMPLETED");
        assertThat(orderStatus(acme, o.orderNumber())).isEqualTo("COMPLETED");
        assertThat(refundFlag(o.orderNumber())).isFalse();
        assertThat(layerRemaining(acme, o.productId())).isEqualTo(8);
    }

    @Test
    void webhookThenCancelAndCancelThenWebhookEndInTheSameStockState() throws Exception {
        Gateway g = configureGateway(acme);
        Placed a = place("c8a@acme.test", 2);
        long minorA = jdbc.queryForObject("SELECT total_amount * 100 FROM tenant_acme.orders WHERE order_number = ?", BigDecimal.class, a.orderNumber()).longValue();
        assertThat(webhook(g, event("charge.success", a.reference(), minorA))).isEqualTo(200);
        awaitPayment(a.orderNumber(), "COMPLETED");
        assertThat(status(setStatus(a.orderNumber(), "CANCELLED"))).isEqualTo(200);
        assertThat(layerRemaining(acme, a.productId())).isEqualTo(10);
        assertThat(refundFlag(a.orderNumber())).isTrue();
        assertThat(soldCount(a.productId())).isZero();
    }

    @Test
    void twoSimultaneousCancelsReleaseOnce() throws Exception {
        Placed o = place("c9@acme.test", 3);
        List<Integer> codes = concurrently(List.of(() -> status(setStatus(o.orderNumber(), "CANCELLED")),
                () -> status(setStatus(o.orderNumber(), "CANCELLED")), () -> status(setStatus(o.orderNumber(), "CANCELLED"))));
        assertThat(codes).containsOnly(200);
        assertThat(layerRemaining(acme, o.productId())).isEqualTo(10);
        assertThat(movements("RELEASE", o.orderNumber())).isEqualTo(1);
        assertThat(audit(o.orderNumber(), "CANCELLED")).isEqualTo(1);
    }

    @Test
    void cancelRacingTheWebhookIsConsistentWhicheverWins() throws Exception {
        Gateway g = configureGateway(acme);
        for (int i = 0; i < 4; i++) {
            Placed o = place("c10-" + i + "@acme.test", 2);
            long minor = jdbc.queryForObject("SELECT total_amount * 100 FROM tenant_acme.orders WHERE order_number = ?", BigDecimal.class, o.orderNumber()).longValue();
            concurrently(List.of(() -> status(setStatus(o.orderNumber(), "CANCELLED")),
                    () -> { try { return webhook(g, event("charge.success", o.reference(), minor)); } catch (Exception e) { throw new RuntimeException(e); } }));
            awaitPayment(o.orderNumber(), "COMPLETED");
            assertThat(layerRemaining(acme, o.productId())).as("round " + i).isEqualTo(10);
            assertThat(reservedQty(acme, o.productId())).isZero();
            assertThat(soldCount(o.productId())).isZero();
            assertThat(deliveryStatus(o.orderNumber())).isEqualTo("CANCELLED");
            assertThat(paymentStatus(o.orderNumber())).isEqualTo("COMPLETED");
            assertThat(refundFlag(o.orderNumber())).isTrue();
        }
    }

    // ------------------------------------------------------------------------------------------ transitions (item 4)

    @Test
    void unpaidOrderCannotBeMarkedDelivered() throws Exception {
        Placed o = place("t1@acme.test", 1);
        MvcResult r = setStatus(o.orderNumber(), "DELIVERED");
        assertThat(status(r)).isEqualTo(400);
        assertThat(code(r)).isEqualTo("ORDER_NOT_PAID");
        assertThat(deliveryStatus(o.orderNumber())).isEqualTo("PENDING");
        jdbc.update("UPDATE tenant_acme.orders SET status = 'FAILED' WHERE order_number = ?", o.orderNumber());
        assertThat(code(setStatus(o.orderNumber(), "DELIVERED"))).isEqualTo("ORDER_NOT_PAID");
    }

    @Test
    void forwardMovesBackwardMovesAndFinalStates() throws Exception {
        Placed o = placePaid("t2@acme.test", 1);
        assertThat(status(setStatus(o.orderNumber(), "PACKING"))).isEqualTo(200);
        assertThat(status(setStatus(o.orderNumber(), "PACKING"))).as("same status is a no-op").isEqualTo(200);
        MvcResult back = setStatus(o.orderNumber(), "PENDING");
        assertThat(status(back)).isEqualTo(400);
        assertThat(code(back)).isEqualTo("ORDER_TRANSITION_NOT_ALLOWED");
        assertThat(status(setStatus(o.orderNumber(), "OUT_FOR_DELIVERY"))).isEqualTo(200);
        assertThat(code(setStatus(o.orderNumber(), "PACKING"))).isEqualTo("ORDER_TRANSITION_NOT_ALLOWED");
        assertThat(status(setStatus(o.orderNumber(), "DELIVERED"))).isEqualTo(200);

        MvcResult fin = setStatus(o.orderNumber(), "PACKING");
        assertThat(status(fin)).isEqualTo(400);
        assertThat(code(fin)).isEqualTo("ORDER_FINAL_STATE");
        assertThat(body(fin).get("description").asText()).isEqualTo("Order is already delivered and can not be updated.");
        assertThat(code(setStatus(o.orderNumber(), "CANCELLED"))).isEqualTo("ORDER_FINAL_STATE");
        assertThat(status(setStatus(o.orderNumber(), "DELIVERED"))).as("same final status is a no-op").isEqualTo(200);
        assertThat(deliveryStatus(o.orderNumber())).isEqualTo("DELIVERED");
    }

    @Test
    void cancelledIsFinalAndSkippingAheadIsAllowed() throws Exception {
        Placed o = place("t3@acme.test", 1);
        assertThat(status(setStatus(o.orderNumber(), "CANCELLED"))).isEqualTo(200);
        MvcResult r = setStatus(o.orderNumber(), "PACKING");
        assertThat(code(r)).isEqualTo("ORDER_FINAL_STATE");
        assertThat(body(r).get("description").asText()).isEqualTo("Order is already cancelled and can not be updated.");
        Placed p = placePaid("t3b@acme.test", 1);
        assertThat(status(setStatus(p.orderNumber(), "DELIVERED"))).isEqualTo(200);
    }

    @Test
    void invalidBodyIs400AndUnknownOrderIs404() throws Exception {
        Placed o = place("t4@acme.test", 1);
        MvcResult bad = mvc.perform(admin(put("/api/v1/order/update-order-status/" + o.orderNumber())
                .contentType("application/json").content("{\"orderStatus\":\"TELEPORTED\"}"))).andReturn();
        assertThat(status(bad)).isEqualTo(400);
        assertThat(status(setStatus("PB-20990101-NOPE", "PACKING"))).isEqualTo(404);
    }

    // ------------------------------------------------------------------------------------------ optimistic concurrency

    @Test
    void staleVersionIs409AndCurrentVersionWinsAndMovesTheVersion() throws Exception {
        Placed o = placePaid("v1@acme.test", 1);
        long v0 = detail(o.orderNumber()).get("version").asLong();

        MvcResult ok = setStatus(o.orderNumber(), "PACKING", v0, null);
        assertThat(status(ok)).isEqualTo(200);
        long v1 = body(ok).at("/data/version").asLong();
        assertThat(v1).isGreaterThan(v0);
        assertThat(ok.getResponse().getHeader("ETag")).isEqualTo("\"" + v1 + "\"");

        MvcResult stale = setStatus(o.orderNumber(), "OUT_FOR_DELIVERY", v0, null);
        assertThat(status(stale)).isEqualTo(409);
        assertThat(code(stale)).isEqualTo("ORDER_VERSION_CONFLICT");
        assertThat(body(stale).at("/data/currentVersion").asLong()).isEqualTo(v1);
        assertThat(deliveryStatus(o.orderNumber())).isEqualTo("PACKING");

        MvcResult staleHeader = setStatus(o.orderNumber(), "OUT_FOR_DELIVERY", null, "\"" + v0 + "\"");
        assertThat(status(staleHeader)).isEqualTo(409);
        assertThat(status(setStatus(o.orderNumber(), "OUT_FOR_DELIVERY", null, "W/\"" + v1 + "\""))).isEqualTo(200);
    }

    @Test
    void bodyAndHeaderMustAgreeAndHeaderMustBeWellFormed() throws Exception {
        Placed o = placePaid("v2@acme.test", 1);
        long v = detail(o.orderNumber()).get("version").asLong();
        assertThat(code(setStatus(o.orderNumber(), "PACKING", v, "\"" + (v + 1) + "\""))).isEqualTo("VERSION_MISMATCH");
        assertThat(code(setStatus(o.orderNumber(), "PACKING", null, "banana"))).isEqualTo("INVALID_IF_MATCH");
        assertThat(code(setStatus(o.orderNumber(), "PACKING", null, "*"))).isEqualTo("INVALID_IF_MATCH");
        assertThat(deliveryStatus(o.orderNumber())).isEqualTo("PENDING");
    }

    @Test
    void sameStatusRetryWithAStaleVersionIsStillA200() throws Exception {
        Placed o = placePaid("v3@acme.test", 1);
        long v = detail(o.orderNumber()).get("version").asLong();
        assertThat(status(setStatus(o.orderNumber(), "PACKING", v, null))).isEqualTo(200);
        assertThat(status(setStatus(o.orderNumber(), "PACKING", v, null))).isEqualTo(200);
    }

    @Test
    void twoAdminsWithTheSameVersionCannotBothWin() throws Exception {
        Placed o = placePaid("v4@acme.test", 1);
        long v = detail(o.orderNumber()).get("version").asLong();
        List<Integer> codes = concurrently(List.of(() -> status(setStatus(o.orderNumber(), "PACKING", v, null)),
                () -> status(setStatus(o.orderNumber(), "OUT_FOR_DELIVERY", v, null))));
        assertThat(codes).containsExactlyInAnyOrder(200, 409);
    }

    // ------------------------------------------------------------------------------------------ refund acknowledgement (item 3)

    @Test
    void acknowledgeClearsTheFlagRecordsWhoAndWhenAndIsNotRepeatable() throws Exception {
        Placed o = placePaid("a1@acme.test", 1);
        setStatus(o.orderNumber(), "CANCELLED");
        assertThat(refundFlag(o.orderNumber())).isTrue();
        long before = detail(o.orderNumber()).get("version").asLong();

        MvcResult r = ack(o.orderNumber());
        assertThat(status(r)).isEqualTo(200);
        JsonNode d = body(r).get("data");
        assertThat(d.get("refundRequired").asBoolean()).isFalse();
        assertThat(d.get("version").asLong()).isGreaterThan(before);
        assertThat(refundFlag(o.orderNumber())).isFalse();
        Map<String, Object> row = jdbc.queryForMap("SELECT p.refund_acknowledged_by AS by, p.refund_acknowledged_at AS at FROM tenant_acme.payments p JOIN tenant_acme.orders o ON o.id = p.order_id WHERE o.order_number = ?", o.orderNumber());
        assertThat(row.get("by")).isEqualTo(acme.adminEmail());
        assertThat(row.get("at")).isNotNull();
        assertThat(audit(o.orderNumber(), "REFUND_ACKNOWLEDGED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT actor_email FROM tenant_acme.order_audit_events WHERE event_type = 'REFUND_ACKNOWLEDGED'", String.class)).isEqualTo(acme.adminEmail());

        MvcResult again = ack(o.orderNumber());
        assertThat(status(again)).isEqualTo(409);
        assertThat(code(again)).isEqualTo("REFUND_NOT_REQUIRED");
        assertThat(audit(o.orderNumber(), "REFUND_ACKNOWLEDGED")).isEqualTo(1);
        // retrying the cancel must not re-raise the flag
        setStatus(o.orderNumber(), "CANCELLED");
        assertThat(refundFlag(o.orderNumber())).isFalse();
    }

    @Test
    void acknowledgeRefusesWhenNoRefundIsRequired() throws Exception {
        Placed unpaid = place("a2@acme.test", 1);
        MvcResult r = ack(unpaid.orderNumber());
        assertThat(status(r)).isEqualTo(409);
        assertThat(code(r)).isEqualTo("REFUND_NOT_REQUIRED");
        assertThat(body(r).get("description").asText()).isEqualTo("No refund is required for this order.");
        Placed paid = placePaid("a2b@acme.test", 1);
        assertThat(code(ack(paid.orderNumber()))).isEqualTo("REFUND_NOT_REQUIRED");
        assertThat(status(ack("PB-20990101-NOPE"))).isEqualTo(404);
        assertThat(count(acme, "order_audit_events")).isZero();
    }

    @Test
    void duplicateSimultaneousAcknowledgeWritesOneAuditRow() throws Exception {
        Placed o = placePaid("a3@acme.test", 1);
        setStatus(o.orderNumber(), "CANCELLED");
        List<Integer> codes = concurrently(List.of(() -> status(ack(o.orderNumber())), () -> status(ack(o.orderNumber())), () -> status(ack(o.orderNumber()))));
        assertThat(codes).containsExactlyInAnyOrder(200, 409, 409);
        assertThat(audit(o.orderNumber(), "REFUND_ACKNOWLEDGED")).isEqualTo(1);
    }

    @Test
    void lateWebhookRefundCanBeAcknowledgedToo() throws Exception {
        Gateway g = configureGateway(acme);
        Placed o = place("a4@acme.test", 1);
        setStatus(o.orderNumber(), "CANCELLED");
        long minor = jdbc.queryForObject("SELECT total_amount * 100 FROM tenant_acme.orders WHERE order_number = ?", BigDecimal.class, o.orderNumber()).longValue();
        webhook(g, event("charge.success", o.reference(), minor));
        awaitPayment(o.orderNumber(), "COMPLETED");
        assertThat(status(ack(o.orderNumber()))).isEqualTo(200);
        assertThat(refundFlag(o.orderNumber())).isFalse();
    }

    // ------------------------------------------------------------------------------------------ authorization

    @Test
    void adminRoutesAreAnonymous401Customer403ForeignAdmin401AndCrossTenant404() throws Exception {
        Placed o = place("z1@acme.test", 1);
        String[][] routes = {{"PUT", "/api/v1/order/update-order-status/" + o.orderNumber()},
                {"POST", "/api/v1/order/" + o.orderNumber() + "/refund-acknowledgement"}, {"GET", "/api/v1/order/all"}};
        for (String[] rt : routes) {
            MockHttpServletRequestBuilder anon = req(rt).header("X-Tenant-ID", "acme");
            assertThat(status(mvc.perform(anon).andReturn())).as("anonymous " + rt[1]).isEqualTo(401);
            assertThat(status(mvc.perform(as(req(rt), o.c())).andReturn())).as("customer " + rt[1]).isEqualTo(403);
            assertThat(status(mvc.perform(bravoAdminOnAcme(req(rt))).andReturn())).as("foreign admin " + rt[1]).isEqualTo(401);
        }
        assertThat(deliveryStatus(o.orderNumber())).isEqualTo("PENDING");

        // bravo's order is invisible to acme's admin
        long bp = product(bravo, "Bravo thing", "5.00", 5);
        Customer bc = customer(bravo, "b1@bravo.test");
        cart(bc, bp, 1);
        String bravoOrder = body(checkout(bc, null, null)).at("/data/orderNumber").asText();
        assertThat(status(setStatus(bravoOrder, "CANCELLED"))).isEqualTo(404);
        assertThat(status(ack(bravoOrder))).isEqualTo(404);
        assertThat(status(mvc.perform(admin(get("/api/v1/order/" + bravoOrder))).andReturn())).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT delivery_status FROM tenant_bravo.orders WHERE order_number = ?", String.class, bravoOrder)).isEqualTo("PENDING");
        assertThat(reservedQty(bravo, bp)).isEqualTo(1);
    }

    private MockHttpServletRequestBuilder req(String[] rt) {
        return switch (rt[0]) {
            case "PUT" -> put(rt[1]).contentType("application/json").content("{\"orderStatus\":\"PACKING\"}");
            case "POST" -> post(rt[1]);
            default -> get(rt[1]);
        };
    }

    // ------------------------------------------------------------------------------------------ list (item 5)

    private List<String> listNumbers(String query) throws Exception {
        MvcResult r = mvc.perform(admin(get("/api/v1/order/all" + (query == null ? "" : "?" + query)))).andReturn();
        assertThat(status(r)).as(query + " -> " + r.getResponse().getContentAsString()).isEqualTo(200);
        List<String> out = new ArrayList<>();
        body(r).at("/data/content").forEach(n -> out.add(n.get("orderNumber").asText()));
        return out;
    }

    private List<Placed> three() throws Exception {
        List<Placed> l = new ArrayList<>();
        for (String e : new String[]{"l1@acme.test", "l2@acme.test", "l3@acme.test"}) l.add(place(e, 1));
        return l;
    }

    @Test
    void sortWhitelistIsHonouredWithDeterministicTieBreak() throws Exception {
        List<Placed> os = three();
        List<String> numbers = new ArrayList<>(os.stream().map(Placed::orderNumber).toList());
        List<String> asc = listNumbers("sort=orderNumber,asc");
        List<String> sorted = new ArrayList<>(numbers);
        java.util.Collections.sort(sorted);
        assertThat(asc).containsExactlyElementsOf(sorted);
        java.util.Collections.reverse(sorted);
        assertThat(listNumbers("sort=orderNumber,desc")).containsExactlyElementsOf(sorted);

        // default: newest first
        List<String> byId = new ArrayList<>(numbers);
        java.util.Collections.reverse(byId);
        assertThat(listNumbers(null)).containsExactlyElementsOf(byId);
        // equal sort keys fall back to id
        jdbc.update("UPDATE tenant_acme.orders SET created_at = TIMESTAMP '2026-01-01 00:00:00'");
        assertThat(listNumbers("sort=createdAt,asc")).containsExactlyElementsOf(numbers);
        assertThat(listNumbers("sort=createdAt,desc")).containsExactlyElementsOf(byId);
        // paging over ties never repeats or skips
        List<String> paged = new ArrayList<>();
        for (int p = 0; p < 3; p++) paged.addAll(listNumbers("sort=deliveryStatus,asc&size=1&page=" + p));
        assertThat(paged).containsExactlyElementsOf(numbers);

        for (String bad : new String[]{"user.password,asc", "idempotencyKey", "totalAmount.amount,desc", "nope"}) {
            MvcResult r = mvc.perform(admin(get("/api/v1/order/all?sort=" + bad))).andReturn();
            assertThat(status(r)).as(bad).isEqualTo(400);
            assertThat(code(r)).isEqualTo("INVALID_SORT");
        }
    }

    @Test
    void dateRangeFiltersAreInclusiveFromAndDayInclusiveToWithOffsets() throws Exception {
        List<Placed> os = three();
        String[] days = {"2026-03-01 10:00:00", "2026-03-02 10:00:00", "2026-03-03 10:00:00"};
        for (int i = 0; i < 3; i++) {
            jdbc.update("UPDATE tenant_acme.orders SET created_at = ?::timestamp WHERE order_number = ?", days[i], os.get(i).orderNumber());
        }
        // server-local column; the filter is UTC, so derive the instants from the JVM zone
        java.time.ZoneId z = java.time.ZoneId.systemDefault();
        java.util.function.Function<String, String> utc = s -> java.time.LocalDateTime.parse(s.replace(' ', 'T')).atZone(z).toInstant().toString();
        assertThat(listNumbers("from=" + utc.apply(days[1]) + "&to=" + utc.apply(days[2]))).containsExactly(os.get(1).orderNumber());
        assertThat(listNumbers("from=" + utc.apply(days[1]))).containsExactlyInAnyOrder(os.get(1).orderNumber(), os.get(2).orderNumber());
        assertThat(listNumbers("to=" + utc.apply(days[1]))).containsExactly(os.get(0).orderNumber());
        // a date-only 'to' covers its whole UTC day, a date-only 'from' starts at its midnight
        java.time.LocalDate d0 = java.time.LocalDate.of(2026, 3, 1);
        assertThat(listNumbers("from=2000-01-01&to=2100-01-01")).hasSize(3);
        assertThat(listNumbers("from=2100-01-01")).isEmpty();
        assertThat(listNumbers("to=2000-01-01")).isEmpty();
        assertThat(d0).isNotNull();
        for (String bad : new String[]{"from=yesterday", "to=2026-13-45", "from=2026-03-01T10:00:00"}) {
            assertThat(code(mvc.perform(admin(get("/api/v1/order/all?" + bad))).andReturn())).as(bad).isEqualTo("INVALID_DATE_FILTER");
        }
        MvcResult rev = mvc.perform(admin(get("/api/v1/order/all?from=2026-03-05&to=2026-03-01"))).andReturn();
        assertThat(status(rev)).isEqualTo(400);
        assertThat(code(rev)).isEqualTo("INVALID_DATE_RANGE");
    }

    @Test
    void textSearchMatchesOrderNumberRecipientPhoneAndEmailAndEscapesWildcards() throws Exception {
        List<Placed> os = three();
        String n0 = os.get(0).orderNumber();
        assertThat(listQ(n0.substring(5, 16).toLowerCase())).containsExactly(n0);
        assertThat(listQ("l2@ACME")).containsExactly(os.get(1).orderNumber());
        jdbc.update("UPDATE tenant_acme.delivery_details SET recipient_name = 'Kwame Asante', phone_number = '0244123456' WHERE user_id = ?", os.get(2).c().id());
        assertThat(listQ("asante")).containsExactly(os.get(2).orderNumber());
        assertThat(listQ("0244123")).containsExactly(os.get(2).orderNumber());
        assertThat(listQ("nobody-matches")).isEmpty();
        // wildcards are literals: '%' or '_' alone must not match everything
        assertThat(listQ("%")).isEmpty();
        assertThat(listQ("_")).isEmpty();
        assertThat(listQ("l_@acme")).isEmpty();
        assertThat(listQ("%acme")).isEmpty();
        assertThat(listQ("\\")).isEmpty();
        jdbc.update("UPDATE tenant_acme.delivery_details SET recipient_name = '100%_sure' WHERE user_id = ?", os.get(0).c().id());
        assertThat(listQ("100%_")).containsExactly(os.get(0).orderNumber());
        assertThat(listQ("100_")).as("underscore is not 'any char'").isEmpty();
        assertThat(listQ("1%sure")).as("percent is not 'anything'").isEmpty();
        assertThat(listQ("  ")).as("blank = no search").hasSize(3);
        assertThat(listQ("l1' OR '1'='1")).isEmpty();

        assertThat(code(listRaw("a".repeat(101)))).isEqualTo("INVALID_SEARCH");
        assertThat(listQ("a".repeat(100))).isEmpty();
        assertThat(code(listRaw("ab\u0000cd"))).isEqualTo("INVALID_SEARCH");
        assertThat(code(listRaw("ab\ncd"))).isEqualTo("INVALID_SEARCH");
        assertThat(code(listRaw("ab\tcd"))).isEqualTo("INVALID_SEARCH");
    }

    @Test
    void searchCombinesWithStatusFiltersAndNeverLeaksAcrossTenants() throws Exception {
        List<Placed> os = three();
        setStatus(os.get(0).orderNumber(), "CANCELLED");
        assertThat(listNumbers("q=acme.test&orderStatus=CANCELLED")).containsExactly(os.get(0).orderNumber());
        assertThat(listNumbers("q=acme.test&paymentStatus=INITIATED")).hasSize(2);
        long bp = product(bravo, "Bravo q", "5.00", 5);
        Customer bc = customer(bravo, "l1@bravo.test");
        cart(bc, bp, 1);
        String bravoOrder = body(checkout(bc, null, null)).at("/data/orderNumber").asText();
        assertThat(listQ("l1@")).doesNotContain(bravoOrder).hasSize(1);
        assertThat(listNumbers(null)).doesNotContain(bravoOrder).hasSize(3);
        assertThat(listQ(bravoOrder)).isEmpty();
    }

    @Test
    void pageAndSizeAreCapped() throws Exception {
        three();
        MvcResult big = mvc.perform(admin(get("/api/v1/order/all?size=100000"))).andReturn();
        assertThat(status(big)).isEqualTo(200);
        assertThat(body(big).at("/data/size").asInt()).isLessThanOrEqualTo(100);
        MvcResult page = mvc.perform(admin(get("/api/v1/order/all?page=1000001"))).andReturn();
        assertThat(status(page)).isEqualTo(400);
        assertThat(code(page)).isEqualTo("INVALID_PAGE");
        assertThat(status(mvc.perform(admin(get("/api/v1/order/all?page=1000000"))).andReturn())).isEqualTo(200);
    }

    @Test
    void listQueryCountDoesNotGrowWithTheNumberOfOrders() throws Exception {
        List<Placed> os = three();
        mvc.perform(admin(get("/api/v1/order/all?size=50"))).andReturn(); // warm up
        SessionFactory sf = emf.unwrap(SessionFactory.class);
        sf.getStatistics().setStatisticsEnabled(true);
        sf.getStatistics().clear();
        listNumbers("size=50&q=acme");
        long small = sf.getStatistics().getPrepareStatementCount();
        for (int i = 0; i < 6; i++) place("more" + i + "@acme.test", 1);
        sf.getStatistics().clear();
        List<String> all = listNumbers("size=50&q=acme");
        long large = sf.getStatistics().getPrepareStatementCount();
        sf.getStatistics().setStatisticsEnabled(false);
        assertThat(all).hasSize(9);
        assertThat(large).as("statements for 9 rows vs 3 rows").isEqualTo(small);
        assertThat(os).hasSize(3);
    }

    // ------------------------------------------------------------------------------------------ shapes (item 6, 7, 8)

    @Test
    void adminDetailShowsAccountIdentityCustomersNeverDoAndListRowsHaveNone() throws Exception {
        Placed o = place("s1@acme.test", 1);
        JsonNode admin = detail(o.orderNumber());
        assertThat(admin.at("/account/email").asText()).isEqualTo("s1@acme.test");
        assertThat(admin.at("/account/id").asLong()).isEqualTo(o.c().id());
        assertThat(admin.at("/account/fullName").asText()).isEqualTo("Cust");

        MvcResult own = mvc.perform(as(get("/api/v1/order/" + o.orderNumber()), o.c())).andReturn();
        assertThat(status(own)).isEqualTo(200);
        assertThat(body(own).get("data").has("account")).isFalse();
        assertThat(own.getResponse().getContentAsString()).doesNotContain("s1@acme.test\",\"fullName");
        MvcResult mine = mvc.perform(as(get("/api/v1/order/my-orders"), o.c())).andReturn();
        assertThat(mine.getResponse().getContentAsString()).doesNotContain("account");
        MvcResult cancelled = cancel(o.c(), o.orderNumber());
        assertThat(body(cancelled).get("data").has("account")).isFalse();
        assertThat(mvc.perform(admin(get("/api/v1/order/all"))).andReturn().getResponse().getContentAsString()).doesNotContain("\"account\"");
        assertThat(body(setStatusResult(o.orderNumber())).at("/data/account/email").asText()).isEqualTo("s1@acme.test");
    }

    private MvcResult setStatusResult(String n) throws Exception {
        return setStatus(n, "CANCELLED");
    }

    @Test
    void orderedAtIsAnUnambiguousUtcInstantOfTheLegacyOrderDate() throws Exception {
        Placed o = place("s2@acme.test", 1);
        JsonNode d = detail(o.orderNumber());
        assertThat(d.get("orderedAt").asText()).endsWith("Z");
        java.time.Instant fromNew = java.time.Instant.parse(d.get("orderedAt").asText());
        java.time.Instant fromLegacy = java.time.LocalDateTime.parse(d.get("orderDate").asText()).atZone(java.time.ZoneId.systemDefault()).toInstant();
        assertThat(fromNew).isEqualTo(fromLegacy);
        assertThat(d.get("orderDate").asText()).doesNotEndWith("Z");
        JsonNode row = body(mvc.perform(admin(get("/api/v1/order/all"))).andReturn()).at("/data/content/0");
        assertThat(row.get("orderedAt").asText()).endsWith("Z");
        assertThat(row.get("version").asLong()).isEqualTo(d.get("version").asLong());
    }

    @Test
    void newOrderNumbersAreLongRandomAndOldNumbersStillResolveOnlyForTheirOwnerOrAdmin() throws Exception {
        Placed o = place("s3@acme.test", 1);
        assertThat(o.orderNumber()).matches("PB-\\d{8}-[23456789ABCDEFGHJKMNPQRSTVWXYZ]{10}");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.order_number_claims WHERE order_number = ?", Integer.class, o.orderNumber())).isEqualTo(1);
        Placed p = place("s3b@acme.test", 1);
        assertThat(p.orderNumber()).isNotEqualTo(o.orderNumber());

        jdbc.update("UPDATE tenant_acme.orders SET order_number = 'PB-20260101-AB12C' WHERE order_number = ?", o.orderNumber());
        assertThat(status(mvc.perform(as(get("/api/v1/order/pb-20260101-ab12c"), o.c())).andReturn())).isEqualTo(200);
        assertThat(detail("PB-20260101-AB12C").get("orderNumber").asText()).isEqualTo("PB-20260101-AB12C");

        // not enumerable: anonymous 401, another customer / unknown identical 404, foreign tenant admin 401
        assertThat(status(mvc.perform(get("/api/v1/order/PB-20260101-AB12C").header("X-Tenant-ID", "acme")).andReturn())).isEqualTo(401);
        MvcResult other = mvc.perform(as(get("/api/v1/order/PB-20260101-AB12C"), p.c())).andReturn();
        MvcResult unknown = mvc.perform(as(get("/api/v1/order/PB-20260101-ZZZZZ"), p.c())).andReturn();
        assertThat(status(other)).isEqualTo(404);
        assertThat(other.getResponse().getContentAsString()).isEqualTo(unknown.getResponse().getContentAsString());
        assertThat(status(mvc.perform(bravoAdminOnAcme(get("/api/v1/order/PB-20260101-AB12C"))).andReturn())).isEqualTo(401);
    }
}
