package com.example.perfume_budget.checkout;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * PAY-03: at most ONE open (INITIATED, inside the reservation TTL) payable order per customer, whatever
 * Idempotency-Keys the tabs / devices send. Same cart (lines + coupon + address) -> that order is returned
 * ({@code idempotentReplay: true}, no second Paystack call, no second stock / coupon reservation); a different cart ->
 * 409 {@code OPEN_ORDER_EXISTS} with {@code data.orderNumber}, nothing created or reserved. Real Postgres, tenants
 * acme + bravo, Paystack stubbed.
 */
class OpenOrderReuseIntegrationTest extends AbstractCheckoutIntegrationTest {

    private Customer ready(String email, long productId, int qty) {
        Customer c = customer(acme, email);
        cart(c, productId, qty);
        return c;
    }

    private JsonNode ok(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r).get("data");
    }

    private void assertOpenOrderExists(MvcResult r, String orderNumber) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(409);
        JsonNode b = body(r);
        assertThat(b.at("/data/code").asText()).isEqualTo("OPEN_ORDER_EXISTS");
        assertThat(b.at("/data/orderNumber").asText()).isEqualTo(orderNumber);
        assertThat(b.at("/data/expiresAt").asText()).endsWith("Z");
    }

    private int couponUsage(String code) {
        return jdbc.queryForObject("SELECT usage_count FROM tenant_acme.coupons WHERE code = ?", Integer.class, code);
    }

    private int openOrders(Customer c) {
        return jdbc.queryForObject("SELECT count(*) FROM " + c.tenant().schemaName()
                + ".orders WHERE user_id = ? AND status = 'INITIATED'", Integer.class, c.id());
    }

    private int stockOf(long productId) {
        return jdbc.queryForObject("SELECT stock_quantity FROM tenant_acme.products WHERE id = ?", Integer.class, productId);
    }

    private long totalMinor(String orderNumber) {
        return jdbc.queryForObject("SELECT total_amount * 100 FROM tenant_acme.orders WHERE order_number = ?",
                BigDecimal.class, orderNumber).longValue();
    }

    // ---- same cart, different keys -> the open order is reused ----

    @Test
    void twoKeysSameCartReturnTheSameOrderWithoutASecondProviderCallOrReservation() throws Exception {
        long p = product(acme, "Reuse One", "50.00", 10);
        Customer c = ready("r1@acme.test", p, 2);

        JsonNode first = ok(checkout(c, "tab-a-key-0001", null));
        JsonNode second = ok(checkout(c, "tab-b-key-0001", null));

        assertThat(first.get("idempotentReplay").asBoolean()).isFalse();
        assertThat(second.get("idempotentReplay").asBoolean()).isTrue();
        assertThat(second.get("orderNumber").asText()).isEqualTo(first.get("orderNumber").asText());
        assertThat(second.get("orderId").asLong()).isEqualTo(first.get("orderId").asLong());
        assertThat(second.at("/data/authorization_url").asText()).isEqualTo(first.at("/data/authorization_url").asText());
        assertThat(second.at("/data/reference").asText()).isEqualTo(first.at("/data/reference").asText());
        assertThat(second.get("expiresAt").asText()).isEqualTo(first.get("expiresAt").asText());
        assertThat(second.get("totalAmount").asText()).isEqualTo(first.get("totalAmount").asText());
        assertThat(count(acme, "orders")).isEqualTo(1);
        assertThat(count(acme, "payments")).isEqualTo(1);
        assertThat(initiateCalls.get()).isEqualTo(1);
        assertThat(layerRemaining(acme, p)).isEqualTo(8);
        assertThat(reservedQty(acme, p)).isEqualTo(2);

        // the reused key is not bound to the order: re-sending it while the order is open returns that order again
        JsonNode again = ok(checkout(c, "tab-b-key-0001", null));
        assertThat(again.get("orderNumber").asText()).isEqualTo(first.get("orderNumber").asText());
        // and the original key keeps its own replay semantics
        assertThat(ok(checkout(c, "tab-a-key-0001", null)).get("idempotentReplay").asBoolean()).isTrue();
        // a keyless submit of the same cart also lands on it
        assertThat(ok(checkout(c, null, null)).get("orderNumber").asText()).isEqualTo(first.get("orderNumber").asText());
        assertThat(count(acme, "orders")).isEqualTo(1);
        assertThat(initiateCalls.get()).isEqualTo(1);
    }

    @Test
    void sameCartWithCouponReusedAcrossKeysRedeemsTheCouponOnce() throws Exception {
        long p = product(acme, "Reuse Coupon", "100.00", 10);
        coupon(acme, "TABS10", 10);
        Customer c = ready("r2@acme.test", p, 1);

        JsonNode first = ok(checkout(c, "tab-a-key-0002", "couponCode=TABS10"));
        // coupon case-insensitive, same request
        JsonNode second = ok(checkout(c, "tab-b-key-0002", "couponCode=tabs10"));

        assertThat(second.get("orderNumber").asText()).isEqualTo(first.get("orderNumber").asText());
        assertThat(second.get("idempotentReplay").asBoolean()).isTrue();
        assertThat(couponUsage("TABS10")).isEqualTo(1);
        assertThat(count(acme, "orders")).isEqualTo(1);
        assertThat(initiateCalls.get()).isEqualTo(1);
        assertThat(layerRemaining(acme, p)).isEqualTo(9);
    }

    @Test
    void concurrentCheckoutsWithDifferentKeysAndTheSameCartCreateExactlyOneOpenOrder() throws Exception {
        initiateDelayMillis = 300;
        long p = product(acme, "Reuse Race", "50.00", 10);
        coupon(acme, "RACE10", 10);
        Customer c = ready("r3@acme.test", p, 2);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Callable<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String key = "race-tab-key-" + i + "-0003";
                calls.add(() -> {
                    go.await();
                    return checkout(c, key, "couponCode=RACE10");
                });
            }
            List<Future<MvcResult>> fs = new ArrayList<>();
            for (Callable<MvcResult> call : calls) fs.add(pool.submit(call));
            go.countDown();
            Set<String> numbers = new HashSet<>();
            int fresh = 0;
            for (Future<MvcResult> f : fs) {
                JsonNode d = ok(f.get());
                numbers.add(d.get("orderNumber").asText());
                if (!d.get("idempotentReplay").asBoolean()) fresh++;
            }
            assertThat(numbers).hasSize(1);
            assertThat(fresh).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count(acme, "orders")).isEqualTo(1);
        assertThat(openOrders(c)).isEqualTo(1);
        assertThat(initiateCalls.get()).isEqualTo(1);
        assertThat(layerRemaining(acme, p)).isEqualTo(8);
        assertThat(reservedQty(acme, p)).isEqualTo(2);
        assertThat(couponUsage("RACE10")).as("redemptions reserved by the losers are given back").isEqualTo(1);
    }

    // ---- different cart -> 409 OPEN_ORDER_EXISTS, nothing created ----

    @Test
    void differentCartWithANewKeyIs409OpenOrderExistsAndReservesNothing() throws Exception {
        long p = product(acme, "Diff One", "50.00", 10);
        long q = product(acme, "Diff Two", "20.00", 5);
        coupon(acme, "DIFF10", 10);
        Customer c = ready("r4@acme.test", p, 1);
        String n1 = ok(checkout(c, "tab-a-key-0004", null)).get("orderNumber").asText();

        cart(c, q, 1); // the other tab added an item
        assertOpenOrderExists(checkout(c, "tab-b-key-0004", null), n1);
        // a different coupon on the same lines is a different cart too; its redemption is never taken
        jdbc.update("DELETE FROM tenant_acme.cart_items WHERE product_id = ?", q);
        assertOpenOrderExists(checkout(c, "tab-b-key-0005", "couponCode=DIFF10"), n1);
        // so is a different delivery address
        long other = extraAddress(c);
        assertOpenOrderExists(checkout(c, "tab-b-key-0006", "deliveryDetailId=" + other), n1);
        // and a different quantity
        jdbc.update("UPDATE tenant_acme.cart_items SET quantity = 2 WHERE product_id = ?", p);
        assertOpenOrderExists(checkout(c, "tab-b-key-0007", null), n1);

        assertThat(count(acme, "orders")).isEqualTo(1);
        assertThat(initiateCalls.get()).isEqualTo(1);
        assertThat(layerRemaining(acme, p)).isEqualTo(9);
        assertThat(reservedQty(acme, q)).isZero();
        assertThat(stockOf(q)).isEqualTo(5);
        assertThat(couponUsage("DIFF10")).isZero();
        assertThat(orderStatus(acme, n1)).as("the open order is left alone").isEqualTo("INITIATED");

        // the refused key is not burnt: after the customer cancels the open order it creates the new one
        assertThat(cancel(c, n1).getResponse().getStatus()).isEqualTo(200);
        JsonNode fresh = ok(checkout(c, "tab-b-key-0007", null));
        assertThat(fresh.get("idempotentReplay").asBoolean()).isFalse();
        assertThat(fresh.get("orderNumber").asText()).isNotEqualTo(n1);
        assertThat(openOrders(c)).isEqualTo(1);
    }

    @Test
    void differentCartWithoutAKeyIsAlso409() throws Exception {
        long p = product(acme, "Keyless One", "50.00", 10);
        Customer c = ready("r5@acme.test", p, 1);
        String n1 = ok(checkout(c, null, null)).get("orderNumber").asText();
        long q = product(acme, "Keyless Two", "20.00", 5);
        cart(c, q, 1);
        assertOpenOrderExists(checkout(c, null, null), n1);
        assertThat(count(acme, "orders")).isEqualTo(1);
        assertThat(reservedQty(acme, q)).isZero();
        assertThat(stockOf(q)).isEqualTo(5);
    }

    @Test
    void concurrentCheckoutsWithDifferentCartsLeaveOneOrderAndTheRest409() throws Exception {
        initiateDelayMillis = 300;
        long p = product(acme, "Diff Race", "50.00", 10);
        coupon(acme, "DRACE10", 10);
        Customer c = ready("r6@acme.test", p, 1);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        List<MvcResult> results = new ArrayList<>();
        try {
            List<Future<MvcResult>> fs = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String key = "drace-key-" + i + "-0006";
                String query = i % 2 == 0 ? null : "couponCode=DRACE10";
                fs.add(pool.submit(() -> {
                    go.await();
                    return checkout(c, key, query);
                }));
            }
            go.countDown();
            for (Future<MvcResult> f : fs) results.add(f.get());
        } finally {
            pool.shutdownNow();
        }
        assertThat(count(acme, "orders")).isEqualTo(1);
        assertThat(openOrders(c)).isEqualTo(1);
        assertThat(initiateCalls.get()).isEqualTo(1);
        String winner = jdbc.queryForObject("SELECT order_number FROM tenant_acme.orders", String.class);
        int conflicts = 0;
        for (MvcResult r : results) {
            int s = r.getResponse().getStatus();
            assertThat(s).as(r.getResponse().getContentAsString()).isIn(200, 409);
            if (s == 409) {
                assertOpenOrderExists(r, winner);
                conflicts++;
            } else {
                assertThat(body(r).at("/data/orderNumber").asText()).isEqualTo(winner);
            }
        }
        assertThat(conflicts).isEqualTo(2);
        assertThat(layerRemaining(acme, p)).isEqualTo(9);
        boolean couponWon = jdbc.queryForObject("SELECT coupon_id IS NOT NULL FROM tenant_acme.orders", Boolean.class);
        assertThat(couponUsage("DRACE10")).isEqualTo(couponWon ? 1 : 0);
    }

    // ---- the old order is no longer open -> a new order ----

    @Test
    void cancelledOrderDoesNotBlockANewOrder() throws Exception {
        long p = product(acme, "Closed One", "50.00", 10);
        Customer c = ready("r7@acme.test", p, 1);
        String n1 = ok(checkout(c, "tab-a-key-0007", null)).get("orderNumber").asText();
        assertThat(cancel(c, n1).getResponse().getStatus()).isEqualTo(200);

        JsonNode d = ok(checkout(c, "tab-b-key-0008", null));
        assertThat(d.get("orderNumber").asText()).isNotEqualTo(n1);
        assertThat(d.get("idempotentReplay").asBoolean()).isFalse();
        assertThat(initiateCalls.get()).isEqualTo(2);
        assertThat(layerRemaining(acme, p)).isEqualTo(9);
        // the old key is still bound to its closed order
        MvcResult old = checkout(c, "tab-a-key-0007", null);
        assertThat(old.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(old).at("/data/code").asText()).isEqualTo("IDEMPOTENT_ORDER_CLOSED");
    }

    @Test
    void expiredButNotYetCancelledOrderDoesNotBlockANewOrder() throws Exception {
        long p = product(acme, "Expired One", "50.00", 10);
        long q = product(acme, "Expired Two", "20.00", 5);
        Customer c = ready("r8@acme.test", p, 1);
        String n1 = ok(checkout(c, "tab-a-key-0009", null)).get("orderNumber").asText();
        // past the reservation TTL (1 h), the expiry job has not run yet
        jdbc.update("UPDATE tenant_acme.orders SET created_at = now() - interval '61 minutes' WHERE order_number = ?", n1);

        JsonNode same = ok(checkout(c, "tab-b-key-0009", null));
        assertThat(same.get("orderNumber").asText()).isNotEqualTo(n1);
        assertThat(same.get("idempotentReplay").asBoolean()).isFalse();

        // the new order is the open one now: a different cart is refused against IT, not the expired one
        cart(c, q, 1);
        assertOpenOrderExists(checkout(c, "tab-c-key-0009", null), same.get("orderNumber").asText());
        assertThat(count(acme, "orders")).isEqualTo(2);
    }

    @Test
    void paidOrderDoesNotBlockANewOrder() throws Exception {
        Gateway g = configureGateway(acme);
        long p = product(acme, "Paid One", "50.00", 10);
        Customer c = ready("r9@acme.test", p, 1);
        JsonNode first = ok(checkout(c, "tab-a-key-0010", null));
        String n1 = first.get("orderNumber").asText();
        webhook(g, event("charge.success", first.at("/data/reference").asText(), totalMinor(n1)));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(orderStatus(acme, n1)).isEqualTo("COMPLETED"));
        awaitBackgroundIdle();
        assertThat(count(acme, "cart_items")).as("payment success empties the cart").isZero();
        cart(c, p, 1); // the same cart again

        JsonNode d = ok(checkout(c, "tab-b-key-0010", null));
        assertThat(d.get("orderNumber").asText()).isNotEqualTo(n1);
        assertThat(d.get("idempotentReplay").asBoolean()).isFalse();
        assertThat(initiateCalls.get()).isEqualTo(2);
    }

    @Test
    void failedOrderDoesNotBlockANewOrder() throws Exception {
        Gateway g = configureGateway(acme);
        long p = product(acme, "Failed One", "50.00", 10);
        Customer c = ready("r10@acme.test", p, 1);
        JsonNode first = ok(checkout(c, null, null));
        String n1 = first.get("orderNumber").asText();
        webhook(g, event("charge.failed", first.at("/data/reference").asText(), totalMinor(n1)));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(orderStatus(acme, n1)).isEqualTo("FAILED"));

        JsonNode d = ok(checkout(c, "tab-b-key-0011", null));
        assertThat(d.get("orderNumber").asText()).isNotEqualTo(n1);
        assertThat(layerRemaining(acme, p)).isEqualTo(9);
    }

    @Test
    void withTwoOpenOrdersFromBeforePay03TheNewestIsReturnedOrNamed() throws Exception {
        long p = product(acme, "Legacy One", "50.00", 10);
        Customer c = ready("r15@acme.test", p, 1);
        String older = ok(checkout(c, "legacy-key-0001", null)).get("orderNumber").asText();
        jdbc.update("UPDATE tenant_acme.orders SET created_at = now() - interval '2 hours' WHERE order_number = ?", older);
        String newer = ok(checkout(c, "legacy-key-0002", null)).get("orderNumber").asText();
        // data written before PAY-03 could hold two open orders of one customer
        jdbc.update("UPDATE tenant_acme.orders SET created_at = now() - interval '30 minutes' WHERE order_number = ?", older);
        assertThat(openOrders(c)).isEqualTo(2);

        assertThat(ok(checkout(c, "legacy-key-0003", null)).get("orderNumber").asText()).isEqualTo(newer);
        cart(c, product(acme, "Legacy Two", "10.00", 5), 1);
        assertOpenOrderExists(checkout(c, "legacy-key-0004", null), newer);
        assertThat(count(acme, "orders")).isEqualTo(2);
    }

    // ---- same-key semantics are unchanged ----

    @Test
    void sameKeyReplayAndItsErrorsAreUnchanged() throws Exception {
        long p = product(acme, "Same Key", "50.00", 10);
        coupon(acme, "SAME10", 10);
        Customer c = ready("r11@acme.test", p, 1);
        JsonNode first = ok(checkout(c, "same-key-0012", null));
        JsonNode replay = ok(checkout(c, "same-key-0012", null));
        assertThat(replay.get("orderNumber").asText()).isEqualTo(first.get("orderNumber").asText());
        assertThat(replay.get("idempotentReplay").asBoolean()).isTrue();

        // same key, different request: still 422, not the new 409
        MvcResult reused = checkout(c, "same-key-0012", "couponCode=SAME10");
        assertThat(reused.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(reused).at("/data/code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(couponUsage("SAME10")).isZero();
        assertThat(count(acme, "orders")).isEqualTo(1);
        assertThat(initiateCalls.get()).isEqualTo(1);
    }

    // ---- isolation ----

    @Test
    void anotherCustomersOrAnotherTenantsOpenOrderNeverBlocksOrIsReturned() throws Exception {
        long pa = product(acme, "Iso A", "50.00", 10);
        long pb = product(bravo, "Iso B", "50.00", 10);
        Customer c1 = ready("r12@acme.test", pa, 1);
        Customer c2 = ready("r13@acme.test", pa, 1);
        Customer cb = customer(bravo, "r14@bravo.test");
        cart(cb, pb, 1);

        String n1 = ok(checkout(c1, "iso-key-0013", null)).get("orderNumber").asText();
        JsonNode d2 = ok(checkout(c2, "iso-key-0014", null));
        JsonNode db = ok(checkout(cb, "iso-key-0015", null));
        assertThat(d2.get("orderNumber").asText()).isNotEqualTo(n1);
        assertThat(d2.get("idempotentReplay").asBoolean()).isFalse();
        assertThat(db.get("idempotentReplay").asBoolean()).isFalse();
        assertThat(count(acme, "orders")).isEqualTo(2);
        assertThat(count(bravo, "orders")).isEqualTo(1);

        // c2's different cart is refused against c2's own order, never c1's
        cart(c2, product(acme, "Iso A2", "10.00", 5), 1);
        assertOpenOrderExists(checkout(c2, "iso-key-0016", null), d2.get("orderNumber").asText());
        // the bravo customer with an open order in bravo is unaffected by acme's orders and vice versa
        assertThat(ok(checkout(cb, "iso-key-0017", null)).get("orderNumber").asText())
                .isEqualTo(db.get("orderNumber").asText());
        assertThat(initiateCalls.get()).isEqualTo(3);
    }
}
