package com.example.perfume_budget.checkout;

import com.example.perfume_budget.dto.payment.response.PaystackVerifyTransactionResponse;
import com.example.perfume_budget.task.ReservedProductCleanupTask;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** PAY-01 AC1 (verify), AC3 (cancel/expiry, exactly-once release), AC5b (webhook events, late success). */
class PaymentLifecycleIntegrationTest extends AbstractCheckoutIntegrationTest {

    @Autowired ReservedProductCleanupTask cleanupTask;

    private record Placed(Customer c, long productId, String orderNumber, String reference) {}

    private Placed place(String email, int qty) throws Exception {
        long p = product(acme, "Life " + email, "10.00", 10);
        Customer c = customer(acme, email);
        cart(c, p, qty);
        JsonNode d = body(checkout(c, null, null)).get("data");
        return new Placed(c, p, d.get("orderNumber").asText(), d.at("/data/reference").asText());
    }

    private static PaystackVerifyTransactionResponse providerSays(String status, String ref, long minor, String ccy) {
        return new PaystackVerifyTransactionResponse(true, "ok",
                new PaystackVerifyTransactionResponse.Data(status, ref, BigDecimal.valueOf(minor), ccy, "card", "Successful"));
    }

    private long totalMinor(String orderNumber) {
        return jdbc.queryForObject("SELECT total_amount * 100 FROM tenant_acme.orders WHERE order_number = ?", BigDecimal.class, orderNumber).longValue();
    }

    private int movements(String type, String orderNumber) {
        return jdbc.queryForObject("SELECT count(*) FROM tenant_acme.inventory_movements WHERE movement_type = ? AND reference_id = ?", Integer.class, type, orderNumber);
    }

    // ---- verify (AC1) ----

    @Test
    void verifyReportsPendingHonestlyWhenProviderCannotConfirm() throws Exception {
        Placed o = place("v1@acme.test", 1);
        MvcResult r = verify(o.c(), o.reference());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode d = body(r).get("data");
        assertThat(d.get("orderNumber").asText()).isEqualTo(o.orderNumber());
        assertThat(d.get("paymentStatus").asText()).isEqualTo("INITIATED");
        assertThat(d.get("settled").asBoolean()).isFalse();
        assertThat(orderStatus(acme, o.orderNumber())).isEqualTo("INITIATED");
    }

    @Test
    void clientCannotMarkPaidByAnyInput() throws Exception {
        Placed o = place("v2@acme.test", 1);
        mvc.perform(as(get("/api/v1/payment/verify").param("reference", o.reference()).param("status", "success")
                .param("paymentStatus", "COMPLETED").header("X-Payment-Status", "COMPLETED"), o.c())).andReturn();
        assertThat(orderStatus(acme, o.orderNumber())).isEqualTo("INITIATED");
        // provider says abandoned: still pending
        verifyAnswer = providerSays("abandoned", o.reference(), totalMinor(o.orderNumber()), "GHS");
        assertThat(body(verify(o.c(), o.reference())).at("/data/paymentStatus").asText()).isEqualTo("INITIATED");
    }

    @Test
    void verifyAppliesProviderSuccessServerSideOnceAndIdempotently() throws Exception {
        Placed o = place("v3@acme.test", 2);
        verifyAnswer = providerSays("success", o.reference(), totalMinor(o.orderNumber()), "GHS");

        JsonNode d = body(verify(o.c(), o.reference())).get("data");
        assertThat(d.get("paymentStatus").asText()).isEqualTo("COMPLETED");
        assertThat(d.get("settled").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT sold_count FROM tenant_acme.products WHERE id = ?", Integer.class, o.productId())).isEqualTo(2);
        assertThat(reservedQty(acme, o.productId())).isZero();

        verify(o.c(), o.reference());
        verify(o.c(), o.reference());
        assertThat(jdbc.queryForObject("SELECT sold_count FROM tenant_acme.products WHERE id = ?", Integer.class, o.productId())).isEqualTo(2);
        assertThat(verifyCalls.get()).as("settled payments are not re-checked with the provider").isEqualTo(1);
    }

    @Test
    void verifyRejectsProviderAmountOrCurrencyMismatch() throws Exception {
        Placed o = place("v4@acme.test", 1);
        verifyAnswer = providerSays("success", o.reference(), 1, "GHS");
        assertThat(body(verify(o.c(), o.reference())).at("/data/paymentStatus").asText()).isEqualTo("INITIATED");
        Placed o2 = place("v4b@acme.test", 1);
        verifyAnswer = providerSays("success", o2.reference(), totalMinor(o2.orderNumber()), "USD");
        assertThat(body(verify(o2.c(), o2.reference())).at("/data/paymentStatus").asText()).isEqualTo("INITIATED");
        // a provider answer about a different reference is ignored
        Placed o3 = place("v4c@acme.test", 1);
        verifyAnswer = providerSays("success", "T-OTHER-REFERENCE", totalMinor(o3.orderNumber()), "GHS");
        assertThat(body(verify(o3.c(), o3.reference())).at("/data/paymentStatus").asText()).isEqualTo("INITIATED");
    }

    @Test
    void providerCheckIsThrottledPerPayment() throws Exception {
        Placed o = place("v5@acme.test", 1);
        verifyAnswer = providerSays("pending", o.reference(), 0, "GHS");
        for (int i = 0; i < 5; i++) verify(o.c(), o.reference());
        assertThat(verifyCalls.get()).isEqualTo(1);
    }

    @Test
    void verifyIsOwnerAndTenantScopedWithIdentical404() throws Exception {
        Placed o = place("v6@acme.test", 1);
        Customer other = customer(acme, "v6other@acme.test");
        MvcResult cross = verify(other, o.reference());
        MvcResult unknown = verify(other, "T000000000000");
        assertThat(cross.getResponse().getStatus()).isEqualTo(404);
        assertThat(cross.getResponse().getContentAsString()).isEqualTo(unknown.getResponse().getContentAsString());
        assertThat(body(cross).at("/data/code").asText()).isEqualTo("PAYMENT_NOT_FOUND");
        assertThat(verifyCalls.get()).as("a foreign reference never reaches the provider").isZero();
    }

    @Test
    void verifyValidatesReferenceShape() throws Exception {
        Placed o = place("v7@acme.test", 1);
        for (String bad : new String[]{"abc", "has space here", "a/b/../c1234", "x".repeat(101), "<script>1234"}) {
            MvcResult r = verify(o.c(), bad);
            assertThat(r.getResponse().getStatus()).as(bad).isEqualTo(400);
            assertThat(body(r).at("/data/code").asText()).isEqualTo("INVALID_REFERENCE");
        }
        assertThat(mvc.perform(get("/api/v1/payment/verify").param("reference", o.reference()).header("X-Tenant-ID", "acme")).andReturn()
                .getResponse().getStatus()).isIn(401, 403);
    }

    // ---- cancel / expiry (AC3) ----

    @Test
    void ownerCancelReleasesStockOnceAndIsIdempotent() throws Exception {
        Placed o = place("c1@acme.test", 3);
        assertThat(layerRemaining(acme, o.productId())).isEqualTo(7);

        MvcResult r = cancel(o.c(), o.orderNumber());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r).at("/data/paymentStatus").asText()).isEqualTo("CANCELLED");
        assertThat(layerRemaining(acme, o.productId())).isEqualTo(10);
        assertThat(cancel(o.c(), o.orderNumber()).getResponse().getStatus()).isEqualTo(200);
        assertThat(layerRemaining(acme, o.productId())).isEqualTo(10);
        assertThat(movements("RELEASE", o.orderNumber())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM tenant_acme.payments LIMIT 1", String.class)).isEqualTo("CANCELLED");
    }

    @Test
    void cancelOfAnotherCustomersOrUnknownOrderIsTheSame404() throws Exception {
        Placed o = place("c2@acme.test", 1);
        Customer other = customer(acme, "c2other@acme.test");
        MvcResult cross = cancel(other, o.orderNumber());
        MvcResult unknown = cancel(other, "ORD-DOESNOTEXIST");
        assertThat(cross.getResponse().getStatus()).isEqualTo(404);
        assertThat(cross.getResponse().getContentAsString()).isEqualTo(unknown.getResponse().getContentAsString());
        assertThat(orderStatus(acme, o.orderNumber())).isEqualTo("INITIATED");
        assertThat(reservedQty(acme, o.productId())).isEqualTo(1);
    }

    @Test
    void paidOrderCannotBeCancelled() throws Exception {
        Placed o = place("c3@acme.test", 1);
        verifyAnswer = providerSays("success", o.reference(), totalMinor(o.orderNumber()), "GHS");
        verify(o.c(), o.reference());
        MvcResult r = cancel(o.c(), o.orderNumber());
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(r).at("/data/code").asText()).isEqualTo("ORDER_NOT_CANCELLABLE");
        assertThat(orderStatus(acme, o.orderNumber())).isEqualTo("COMPLETED");
    }

    @Test
    void concurrentCancelsAndExpiryReleaseStockExactlyOnce() throws Exception {
        Placed o = place("c4@acme.test", 4);
        jdbc.update("UPDATE tenant_acme.inventory_allocations SET created_at = now() - interval '5 hours'");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Callable<Integer>> work = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final boolean viaJob = i % 3 == 0;
                work.add(() -> {
                    go.await();
                    if (viaJob) {
                        cleanupTask.cleanupExpiredReservations();
                        return 200;
                    }
                    return cancel(o.c(), o.orderNumber()).getResponse().getStatus();
                });
            }
            List<Future<Integer>> fs = new ArrayList<>();
            for (Callable<Integer> w : work) fs.add(pool.submit(w));
            go.countDown();
            for (Future<Integer> f : fs) assertThat(f.get()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(layerRemaining(acme, o.productId())).as("stock restored exactly once").isEqualTo(10);
        assertThat(movements("RELEASE", o.orderNumber())).isEqualTo(movements("RESERVATION", o.orderNumber()));
        assertThat(reservedQty(acme, o.productId())).isZero();
        assertThat(orderStatus(acme, o.orderNumber())).isEqualTo("CANCELLED");
    }

    @Test
    void expiryJobLoopsTenantsAndOnlyTouchesExpiredOrdersOfEachTenant() throws Exception {
        Placed a = place("e1@acme.test", 2);
        Placed freshA = place("e2@acme.test", 1);
        long pb = product(bravo, "Bravo stock", "10.00", 10);
        Customer cb = customer(bravo, "e3@bravo.test");
        cart(cb, pb, 3);
        String bOrder = body(checkout(cb, null, null)).at("/data/orderNumber").asText();

        jdbc.update("UPDATE tenant_acme.inventory_allocations SET created_at = now() - interval '5 hours' WHERE reference_id = ?", a.orderNumber());
        jdbc.update("UPDATE tenant_bravo.inventory_allocations SET created_at = now() - interval '5 hours'");

        cleanupTask.cleanupExpiredReservations();

        assertThat(orderStatus(acme, a.orderNumber())).isEqualTo("CANCELLED");
        assertThat(layerRemaining(acme, a.productId())).isEqualTo(10);
        assertThat(orderStatus(acme, freshA.orderNumber())).isEqualTo("INITIATED");
        assertThat(layerRemaining(acme, freshA.productId())).isEqualTo(9);
        assertThat(orderStatus(bravo, bOrder)).isEqualTo("CANCELLED");
        assertThat(layerRemaining(bravo, pb)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM tenant_acme.payments WHERE order_id = (SELECT id FROM tenant_acme.orders WHERE order_number = ?)",
                String.class, a.orderNumber())).startsWith("Reservation expired after 1 hour");
        // a second run changes nothing
        cleanupTask.cleanupExpiredReservations();
        assertThat(movements("RELEASE", a.orderNumber())).isEqualTo(1);
    }

    // ---- webhook (AC5b) ----

    @Test
    void unknownEventTypesAreIgnoredAndNeverTerminal() throws Exception {
        Gateway g = configureGateway(acme);
        Placed o = place("w1@acme.test", 1);
        for (String type : new String[]{"transfer.success", "refund.processed", "charge.dispute.create", "invoice.create"}) {
            assertThat(webhook(g, event(type, o.reference(), totalMinor(o.orderNumber())))).isEqualTo(200);
        }
        Thread.sleep(1500);
        assertThat(orderStatus(acme, o.orderNumber())).isEqualTo("INITIATED");
        assertThat(reservedQty(acme, o.productId())).isEqualTo(1);
        // and a real success afterwards still completes it
        webhook(g, event("charge.success", o.reference(), totalMinor(o.orderNumber())));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(orderStatus(acme, o.orderNumber())).isEqualTo("COMPLETED"));
    }

    @Test
    void chargeFailedThenLateChargeSuccessCompletesTheOrder() throws Exception {
        Gateway g = configureGateway(acme);
        Placed o = place("w2@acme.test", 2);
        webhook(g, event("charge.failed", o.reference(), totalMinor(o.orderNumber())));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(orderStatus(acme, o.orderNumber())).isEqualTo("FAILED"));
        assertThat(layerRemaining(acme, o.productId())).isEqualTo(10);

        webhook(g, event("charge.success", o.reference(), totalMinor(o.orderNumber())));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(orderStatus(acme, o.orderNumber())).isEqualTo("COMPLETED"));
        assertThat(layerRemaining(acme, o.productId())).as("stock re-reserved and consumed").isEqualTo(8);
        assertThat(reservedQty(acme, o.productId())).isZero();
        assertThat(jdbc.queryForObject("SELECT sold_count FROM tenant_acme.products WHERE id = ?", Integer.class, o.productId())).isEqualTo(2);
        assertThat(body(verify(o.c(), o.reference())).at("/data/paymentStatus").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void lateSuccessAfterCancelCompletesWhenStockAllows() throws Exception {
        Gateway g = configureGateway(acme);
        Placed o = place("w3@acme.test", 2);
        cancel(o.c(), o.orderNumber());
        assertThat(layerRemaining(acme, o.productId())).isEqualTo(10);

        webhook(g, event("charge.success", o.reference(), totalMinor(o.orderNumber())));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(orderStatus(acme, o.orderNumber())).isEqualTo("COMPLETED"));
        assertThat(layerRemaining(acme, o.productId())).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT delivery_status FROM tenant_acme.orders WHERE order_number = ?", String.class, o.orderNumber())).isEqualTo("PENDING");
    }

    @Test
    void lateSuccessWithoutStockKeepsOrderClosedAndFlagsRefund() throws Exception {
        Gateway g = configureGateway(acme);
        Placed o = place("w4@acme.test", 6);
        cancel(o.c(), o.orderNumber());
        // someone else takes the stock meanwhile
        jdbc.update("UPDATE tenant_acme.inventory_layers SET remaining_quantity = 2 WHERE product_id = ?", o.productId());

        webhook(g, event("charge.success", o.reference(), totalMinor(o.orderNumber())));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT refund_required FROM tenant_acme.payments LIMIT 1", Boolean.class)).isTrue());
        assertThat(orderStatus(acme, o.orderNumber())).isEqualTo("CANCELLED");
        assertThat(layerRemaining(acme, o.productId())).as("no partial reservation left behind").isEqualTo(2);
        assertThat(reservedQty(acme, o.productId())).isZero();
        JsonNode d = body(verify(o.c(), o.reference())).get("data");
        assertThat(d.get("refundRequired").asBoolean()).isTrue();
        assertThat(d.get("paymentStatus").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void duplicateAndConcurrentSuccessWebhooksApplyOnce() throws Exception {
        Gateway g = configureGateway(acme);
        Placed o = place("w5@acme.test", 2);
        String body = event("charge.success", o.reference(), totalMinor(o.orderNumber()));
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Future<Integer>> fs = new ArrayList<>();
            for (int i = 0; i < 5; i++) fs.add(pool.submit(() -> webhook(g, body)));
            for (Future<Integer> f : fs) assertThat(f.get()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(orderStatus(acme, o.orderNumber())).isEqualTo("COMPLETED"));
        Thread.sleep(1500);
        assertThat(jdbc.queryForObject("SELECT sold_count FROM tenant_acme.products WHERE id = ?", Integer.class, o.productId())).isEqualTo(2);
        assertThat(layerRemaining(acme, o.productId())).isEqualTo(8);
        assertThat(movements("SALE", o.orderNumber())).isEqualTo(1);
    }

    @Test
    void webhookAmountMismatchIsNotApplied() throws Exception {
        Gateway g = configureGateway(acme);
        Placed o = place("w6@acme.test", 1);
        webhook(g, event("charge.success", o.reference(), 1));
        Thread.sleep(1500);
        assertThat(orderStatus(acme, o.orderNumber())).isEqualTo("INITIATED");
    }

    @Test
    void webhookSignatureAndTokenRoutingStillEnforced() throws Exception {
        Gateway ga = configureGateway(acme);
        Gateway gb = configureGateway(bravo);
        Placed o = place("w7@acme.test", 1);
        String body = event("charge.success", o.reference(), totalMinor(o.orderNumber()));
        // acme's token with bravo's secret, and junk signature: 403, nothing changes
        assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/webhook/" + ga.token())
                .contentType("application/json").header("X-Paystack-Signature", hmac512(body, gb.secret())).content(body)).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/webhook/" + ga.token())
                .contentType("application/json").header("X-Paystack-Signature", "deadbeef").content(body)).andReturn().getResponse().getStatus()).isEqualTo(403);
        // bravo's token + bravo's signature for acme's reference: processed in bravo's schema where it does not exist
        assertThat(webhook(gb, body)).isEqualTo(200);
        Thread.sleep(1500);
        assertThat(orderStatus(acme, o.orderNumber())).isEqualTo("INITIATED");
    }
}
