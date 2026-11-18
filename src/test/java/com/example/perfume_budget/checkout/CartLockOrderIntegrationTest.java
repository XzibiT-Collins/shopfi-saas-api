package com.example.perfume_budget.checkout;

import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.service.EffectivePriceService;
import com.example.perfume_budget.service.PaymentSettlementService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * CAT-04 (CAT-03 review F1 + F2): every cart writer takes the cart row lock FIRST, then touches the lines.
 *
 * <p>Before: add-item locked the cart row and then wrote a line, while clearCart and the payment settlement deleted the
 * lines first and the cart row last. (F1) An add-item of a product already in the cart therefore deadlocked with a
 * concurrent clear / settlement (503 TRY_AGAIN, or a settlement retry). (F2) The deleters removed only the lines they
 * had loaded, so a line added concurrently made the cart row delete fail on {@code fk_cart_items_on_cart}: clear 409,
 * webhook 500 (Paystack redelivers). Now clear and settlement lock the cart row ({@code FOR UPDATE}) first, then bulk
 * delete every line of that cart, then the row.
 *
 * <p>The latch tests park an add-item inside its transaction (holding the cart row lock, line already written) and fire
 * the clear / webhook against it. Every wait is bounded.
 */
class CartLockOrderIntegrationTest extends AbstractCheckoutIntegrationTest {

    @MockitoSpyBean EffectivePriceService effectivePriceService;
    @MockitoSpyBean PaymentSettlementService settlementService;

    static final String BLOCK_THREAD = "cat04-add-blocked";
    static volatile CountDownLatch entered;
    static volatile CountDownLatch release;

    @AfterEach
    void releaseLatch() {
        if (release != null) release.countDown();
        entered = null;
        release = null;
    }

    private void parkAddItemsOnThe(String threadName) {
        doAnswer(inv -> {
            if (Thread.currentThread().getName().equals(threadName) && release != null) {
                entered.countDown();
                if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("cat04 latch timed out");
            }
            return inv.callRealMethod();
        }).when(effectivePriceService).compute(any(Product.class));
    }

    private record Answer(String what, int status, String body) {
        @Override public String toString() {
            return what + "=" + status + (status >= 300 ? " " + body.substring(0, Math.min(200, body.length())) : "");
        }
    }

    private MvcResult addItem(Customer c, long productId, int qty) throws Exception {
        return mvc.perform(as(post("/api/v1/cart/items/add-item").contentType("application/json")
                .content("{\"productId\":" + productId + ",\"quantity\":" + qty + "}"), c)).andReturn();
    }

    private MvcResult populate(Customer c, long... pq) throws Exception {
        StringBuilder sb = new StringBuilder("{\"cartItems\":[");
        for (int i = 0; i < pq.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append("{\"productId\":").append(pq[i]).append(",\"quantity\":").append(pq[i + 1]).append('}');
        }
        sb.append("]}");
        return mvc.perform(as(post("/api/v1/cart/populate").contentType("application/json").content(sb.toString()), c)).andReturn();
    }

    private MvcResult clear(Customer c) throws Exception {
        return mvc.perform(as(delete("/api/v1/cart/clear"), c)).andReturn();
    }

    private static MvcResult status(int s) {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        resp.setStatus(s);
        MvcResult r = org.mockito.Mockito.mock(MvcResult.class);
        org.mockito.Mockito.when(r.getResponse()).thenReturn(resp);
        return r;
    }

    // ---- deadlock counter (pg_stat_database, flushed by idle backends within ~10 s) ----

    private long deadlocks() {
        jdbc.execute("SELECT pg_stat_clear_snapshot()");
        return jdbc.queryForObject("SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()", Long.class);
    }

    /** Deadlocks since {@code before}, read after the idle backends had time to flush their statistics. */
    private long deadlocksSince(long before) throws InterruptedException {
        Thread.sleep(11_000);
        return deadlocks() - before;
    }

    // ---- placing an order to settle ----

    private record Placed(Customer c, long p, String orderNumber, String reference, long minor) {}

    private Placed place(String email, long p, int qty) throws Exception {
        Customer c = customer(acme, email);
        cart(c, p, qty);
        MvcResult r = checkout(c, null, null);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode d = body(r).get("data");
        String on = d.get("orderNumber").asText();
        long minor = jdbc.queryForObject("SELECT total_amount * 100 FROM tenant_acme.orders WHERE order_number = ?",
                BigDecimal.class, on).longValue();
        return new Placed(c, p, on, d.at("/data/reference").asText(), minor);
    }

    private String paymentStatus(String orderNumber) {
        return jdbc.queryForObject("SELECT p.status FROM tenant_acme.payments p JOIN tenant_acme.orders o ON o.id = p.order_id "
                + "WHERE o.order_number = ?", String.class, orderNumber);
    }

    private List<Map<String, Object>> cartRows(Customer c) {
        return jdbc.queryForList("SELECT ca.id, ci.product_id, ci.quantity FROM tenant_acme.carts ca "
                + "LEFT JOIN tenant_acme.cart_items ci ON ci.cart_id = ca.id WHERE ca.user_id = ?", c.id());
    }

    /** Some backend waits on a row lock with a statement on {@code carts} (the clear / settlement reached the cart). */
    private boolean someoneParkedOnTheCart() throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            Thread.sleep(100);
            if (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                    + "AND query ILIKE '%from carts%'", Integer.class) > 0) {
                return true;
            }
        }
        return false;
    }

    // ---- latch reproductions (reviewer probe P3) ----

    /**
     * add-item holds the cart row lock with its line written (same product: an UPDATE flushed at commit; new product: an
     * INSERT already done) and is parked; then the settlement / clear runs and must queue on the cart row BEFORE it
     * touches any line. On release: both succeed, no deadlock, and the cart is gone with every line, including the one
     * the add-item wrote (F2).
     */
    private void deleterWaitsForInFlightAddItem(boolean viaSettlement, boolean sameProduct) throws Exception {
        parkAddItemsOnThe(BLOCK_THREAD);
        Gateway g = viaSettlement ? configureGateway(acme) : null;
        String tag = (viaSettlement ? "settle" : "clear") + "-" + sameProduct;
        long p = product(acme, "CAT04 Scent " + tag, "10.00", 100);
        long p2 = product(acme, "CAT04 Other " + tag, "10.00", 100);
        Placed o = viaSettlement ? place("cat04-" + tag + "@acme.test", p, 1) : null;
        Customer c = viaSettlement ? o.c() : customer(acme, "cat04-" + tag + "@acme.test");
        if (!viaSettlement) cart(c, p, 1);
        clearInvocations(settlementService);
        long d0 = deadlocks();

        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        ExecutorService ex = Executors.newSingleThreadExecutor(r -> new Thread(r, BLOCK_THREAD));
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> add = ex.submit(() -> addItem(c, sameProduct ? p : p2, 1));
            assertThat(entered.await(20, TimeUnit.SECONDS)).as("add-item parked holding the cart lock").isTrue();
            Future<Integer> deleter = viaSettlement
                    ? webhookInBackground(g, event("charge.success", o.reference(), o.minor()))
                    : other.submit(() -> clear(c).getResponse().getStatus());
            boolean parked = someoneParkedOnTheCart();
            release.countDown();
            MvcResult added = add.get(60, TimeUnit.SECONDS);
            int deleted = deleter.get(60, TimeUnit.SECONDS);
            awaitBackgroundIdle();

            assertThat(parked).as("the " + tag + " queued on the cart row").isTrue();
            assertThat(added.getResponse().getStatus()).as(added.getResponse().getContentAsString()).isEqualTo(200);
            assertThat(deleted).as(tag + " answer (F1: was 503 TRY_AGAIN; F2: was 500 / 409 on fk_cart_items_on_cart)")
                    .isEqualTo(viaSettlement ? 200 : 204);
            assertThat(cartRows(c)).as("cart and every line deleted, the concurrently added one too").isEmpty();
            if (viaSettlement) {
                assertThat(paymentStatus(o.orderNumber())).isEqualTo("COMPLETED");
                // F1: a deadlock made the settlement the victim and the webhook retried it (second attempt)
                assertThat(mockingDetails(settlementService).getInvocations().stream()
                        .filter(i -> i.getMethod().getName().equals("applySuccess")).count())
                        .as("settled in one attempt (no deadlock retry)").isEqualTo(1);
            }
            assertThat(deadlocksSince(d0)).as("pg deadlocks during " + tag).isZero();
        } finally {
            release.countDown();
            ex.shutdownNow();
            other.shutdownNow();
        }
    }

    @Test
    void settlementWaitsForAnInFlightAddItemOfAProductAlreadyInTheCart_noDeadlock() throws Exception {
        deleterWaitsForInFlightAddItem(true, true);
    }

    @Test
    void settlementWaitsForAnInFlightAddItemOfANewProduct_deletesThatLineToo_no500() throws Exception {
        deleterWaitsForInFlightAddItem(true, false);
    }

    @Test
    void clearWaitsForAnInFlightAddItemOfAProductAlreadyInTheCart_noDeadlock() throws Exception {
        deleterWaitsForInFlightAddItem(false, true);
    }

    @Test
    void clearWaitsForAnInFlightAddItemOfANewProduct_deletesThatLineToo_noConstraintError() throws Exception {
        deleterWaitsForInFlightAddItem(false, false);
    }

    // ---- mixed concurrent probe (reviewer probe P4) ----

    private List<Answer> race(List<Map.Entry<String, Callable<MvcResult>>> calls) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch ready = new CountDownLatch(calls.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<MvcResult>> fs = new ArrayList<>();
            for (var c : calls) {
                fs.add(ex.submit(() -> {
                    ready.countDown();
                    if (!go.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("start latch timed out");
                    return c.getValue().call();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Answer> out = new ArrayList<>();
            for (int i = 0; i < fs.size(); i++) {
                MvcResult r = fs.get(i).get(90, TimeUnit.SECONDS);
                out.add(new Answer(calls.get(i).getKey(), r.getResponse().getStatus(), r.getResponse().getContentAsString()));
            }
            return out;
        } finally {
            ex.shutdownNow();
        }
    }

    /** Only business answers: 200, 204 (clear / remove), a 404 for a line that is already gone, a 400 refusal, or OPEN_ORDER_EXISTS. */
    private static boolean acceptable(Answer a) {
        return switch (a.status()) {
            case 200 -> true;
            case 204 -> a.what().equals("clear") || a.what().equals("remove");
            case 404 -> a.what().equals("update-qty") || a.what().equals("remove");
            case 400 -> a.what().startsWith("checkout"); // e.g. the cart was emptied by a concurrent clear / settlement
            case 409 -> a.what().startsWith("checkout") && a.body().contains("OPEN_ORDER_EXISTS");
            default -> false;
        };
    }

    @Test
    void mixedConcurrentCartWritesCheckoutAndSettlement_noDeadlock_no5xx() throws Exception {
        Gateway g = configureGateway(acme);
        List<String> report = new ArrayList<>();
        List<Answer> bad = new ArrayList<>();
        long d0 = deadlocks();
        for (int round = 0; round < 6; round++) {
            long p1 = product(acme, "CAT04 A" + round, "10.00", 500);
            long p2 = product(acme, "CAT04 B" + round, "11.00", 500);
            Placed o = place("cat04-mix-" + round + "@acme.test", p1, 1);
            Customer c = o.c();
            long line = jdbc.queryForObject("SELECT ci.id FROM tenant_acme.cart_items ci JOIN tenant_acme.carts ca ON ca.id = ci.cart_id "
                    + "WHERE ca.user_id = ?", Long.class, c.id());
            List<Map.Entry<String, Callable<MvcResult>>> calls = new ArrayList<>();
            calls.add(Map.entry("webhook", () -> status(webhook(g, event("charge.success", o.reference(), o.minor())))));
            for (int i = 0; i < 3; i++) calls.add(Map.entry("add-same", () -> addItem(c, p1, 1)));
            for (int i = 0; i < 2; i++) calls.add(Map.entry("add-new", () -> addItem(c, p2, 1)));
            calls.add(Map.entry("populate", () -> populate(c, p1, 1, p2, 1)));
            calls.add(Map.entry("populate", () -> populate(c, p2, 1, p1, 1)));
            calls.add(Map.entry("update-qty", () -> mvc.perform(as(put("/api/v1/cart/items/update/" + line)
                    .contentType("application/json").content("{\"quantity\":2}"), c)).andReturn()));
            calls.add(Map.entry("remove", () -> mvc.perform(as(delete("/api/v1/cart/items/remove/" + line), c)).andReturn()));
            calls.add(Map.entry("checkout", () -> checkout(c, null, null)));
            calls.add(Map.entry("checkout-key", () -> checkout(c, "k-" + UUID.randomUUID(), null)));
            calls.add(Map.entry("get-cart", () -> mvc.perform(as(get("/api/v1/cart"), c)).andReturn()));
            calls.add(Map.entry("clear", () -> clear(c)));
            calls.add(Map.entry("clear", () -> clear(c)));
            List<Answer> as = race(calls);
            awaitBackgroundIdle();
            String pay = paymentStatus(o.orderNumber());
            report.add("round " + round + " payment=" + pay + " " + as);
            for (Answer a : as) if (!acceptable(a)) bad.add(a);
            if (!"COMPLETED".equals(pay)) bad.add(new Answer("payment-not-completed", 0, pay));
        }
        // first-cart variant: no cart row yet; checkout (user row lock) races the cart creation of add / populate
        for (int round = 0; round < 4; round++) {
            long p1 = product(acme, "CAT04 F" + round, "10.00", 500);
            Customer c = customer(acme, "cat04-first-" + round + "@acme.test");
            List<Map.Entry<String, Callable<MvcResult>>> calls = new ArrayList<>();
            for (int i = 0; i < 3; i++) calls.add(Map.entry("add", () -> addItem(c, p1, 1)));
            calls.add(Map.entry("populate", () -> populate(c, p1, 1)));
            for (int i = 0; i < 2; i++) calls.add(Map.entry("checkout", () -> checkout(c, null, null)));
            calls.add(Map.entry("get-cart", () -> mvc.perform(as(get("/api/v1/cart"), c)).andReturn()));
            calls.add(Map.entry("clear", () -> clear(c)));
            calls.add(Map.entry("remove", () -> mvc.perform(as(delete("/api/v1/cart/items/remove/999999"), c)).andReturn()));
            List<Answer> as = race(calls);
            awaitBackgroundIdle();
            int carts = jdbc.queryForObject("SELECT count(*) FROM tenant_acme.carts WHERE user_id = ?", Integer.class, c.id());
            report.add("first-cart round " + round + " carts=" + carts + " " + as);
            for (Answer a : as) if (!acceptable(a)) bad.add(a);
            if (carts > 1) bad.add(new Answer("carts>1", carts, ""));
        }
        long dl = deadlocksSince(d0);
        report.forEach(l -> System.out.println("CAT04 mixed " + l));
        System.out.println("CAT04 mixed pgDeadlocksDelta=" + dl + " bad=" + bad);
        assertThat(bad).as("no 5xx / 503 TRY_AGAIN / constraint 409 / unsettled payment").isEmpty();
        assertThat(dl).as("pg deadlocks during the mixed probe").isZero();
    }
}
