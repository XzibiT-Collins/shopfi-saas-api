package com.example.perfume_budget.checkout;

import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.repository.CartRepository;
import com.example.perfume_budget.service.EffectivePriceService;
import com.example.perfume_budget.service.PaymentSettlementService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.stubbing.Answer;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
    /** CAT-04 rework: parks a cart writer between its cart lookups (JDK-proxy spy: the default answer delegates). */
    @MockitoSpyBean CartRepository cartRepository;

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

    private record Reply(String what, int status, String body) {
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

    /**
     * Some backend waits on a row lock in CartRemoval's cart-row lock statement ({@code SELECT id FROM carts WHERE
     * user_id = ? FOR UPDATE}): the clear / settlement queued on the cart row. Rework F-B: was {@code '%from carts%'},
     * which the old {@code DELETE FROM carts} (lines first, cart last) matched as well.
     */
    private boolean someoneParkedOnTheCart() throws InterruptedException {
        return waitersOn("SELECT id FROM carts WHERE user_id%FOR UPDATE", 1);
    }

    /** At least {@code n} backends wait on a row lock in a statement matching {@code ILIKE '%<pattern>%'} (5 s max). */
    private boolean waitersOn(String pattern, int n) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            Thread.sleep(100);
            if (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                    + "AND query ILIKE ?", Integer.class, "%" + pattern + "%") >= n) {
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

    private List<Reply> race(List<Map.Entry<String, Callable<MvcResult>>> calls) throws Exception {
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
            List<Reply> out = new ArrayList<>();
            for (int i = 0; i < fs.size(); i++) {
                MvcResult r = fs.get(i).get(90, TimeUnit.SECONDS);
                out.add(new Reply(calls.get(i).getKey(), r.getResponse().getStatus(), r.getResponse().getContentAsString()));
            }
            return out;
        } finally {
            ex.shutdownNow();
        }
    }

    /** Only business answers: 200, 204 (clear / remove), a 404 for a line that is already gone, a 400 refusal, or OPEN_ORDER_EXISTS. */
    private static boolean acceptable(Reply a) {
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
        List<Reply> bad = new ArrayList<>();
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
            List<Reply> as = race(calls);
            awaitBackgroundIdle();
            String pay = paymentStatus(o.orderNumber());
            report.add("round " + round + " payment=" + pay + " " + as);
            for (Reply a : as) if (!acceptable(a)) bad.add(a);
            if (!"COMPLETED".equals(pay)) bad.add(new Reply("payment-not-completed", 0, pay));
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
            List<Reply> as = race(calls);
            awaitBackgroundIdle();
            int carts = jdbc.queryForObject("SELECT count(*) FROM tenant_acme.carts WHERE user_id = ?", Integer.class, c.id());
            report.add("first-cart round " + round + " carts=" + carts + " " + as);
            for (Reply a : as) if (!acceptable(a)) bad.add(a);
            if (carts > 1) bad.add(new Reply("carts>1", carts, ""));
        }
        long dl = deadlocksSince(d0);
        report.forEach(l -> System.out.println("CAT04 mixed " + l));
        System.out.println("CAT04 mixed pgDeadlocksDelta=" + dl + " bad=" + bad);
        assertThat(bad).as("no 5xx / 503 TRY_AGAIN / constraint 409 / unsettled payment").isEmpty();
        assertThat(dl).as("pg deadlocks during the mixed probe").isZero();
    }

    // ---- CAT-04 rework F-A: the cart vanishes between the no-op insert-if-absent and the re-read ----

    static final String VANISH_THREAD = "cat04r1-vanish";

    /** Calls the real repository (the spy of a JDK proxy delegates by default answer; there is no "real method"). */
    private Object real(org.mockito.invocation.InvocationOnMock inv) throws Throwable {
        return mockingDetails(cartRepository).getMockCreationSettings().getDefaultAnswer().answer(inv);
    }

    private static void park(CountDownLatch in, CountDownLatch out) throws InterruptedException {
        in.countDown();
        if (!out.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("cat04r1 latch timed out");
    }

    /**
     * QA F-A (suite b1, mixed first-cart round): request R on {@code VANISH_THREAD} finds no cart (park 1); another
     * request's cart is created and committed meanwhile; R's insert-if-absent then inserts nothing (park 2); a clear /
     * settlement deletes that cart; R's re-read finds no row. Before: IllegalStateException "The cart could not be
     * created." (500) for add-item, populate, update and remove. Now add-item / populate insert again (bounded) and
     * succeed; update / remove never create a cart and answer 404 (no cart, no such line).
     */
    @ParameterizedTest(name = "{0} while a {1} deletes the cart")
    @CsvSource({"add,clear", "add,settle", "populate,clear", "populate,settle",
            "update,clear", "update,settle", "remove,clear", "remove,settle"})
    void cartDeletedBetweenTheNoOpInsertAndTheReRead_no5xx(String writer, String deleter) throws Exception {
        boolean viaSettlement = deleter.equals("settle");
        Gateway g = viaSettlement ? configureGateway(acme) : null;
        String tag = writer + "-" + deleter;
        long p = product(acme, "CAT04R1 " + tag, "10.00", 100);
        Placed o = viaSettlement ? place("cat04r1-" + tag + "@acme.test", p, 1) : null;
        Customer c = viaSettlement ? o.c() : customer(acme, "cat04r1-" + tag + "@acme.test");
        // no cart at the start (checkout only reads the cart, so the placed order's cart is still there: drop it)
        jdbc.update("DELETE FROM tenant_acme.cart_items WHERE cart_id IN (SELECT id FROM tenant_acme.carts WHERE user_id = ?)", c.id());
        jdbc.update("DELETE FROM tenant_acme.carts WHERE user_id = ?", c.id());

        CountDownLatch in1 = new CountDownLatch(1), out1 = new CountDownLatch(1);
        CountDownLatch in2 = new CountDownLatch(1), out2 = new CountDownLatch(1);
        AtomicBoolean parked1 = new AtomicBoolean(), parked2 = new AtomicBoolean();
        Answer<Object> afterLock = inv -> {
            Object r = real(inv);
            if (Thread.currentThread().getName().equals(VANISH_THREAD) && ((java.util.Optional<?>) r).isEmpty()
                    && parked1.compareAndSet(false, true)) {
                park(in1, out1); // R saw no cart
            }
            return r;
        };
        Answer<Object> afterInsert = inv -> {
            Object r = real(inv);
            if (Thread.currentThread().getName().equals(VANISH_THREAD) && parked2.compareAndSet(false, true)) {
                park(in2, out2); // R's insert-if-absent is done (inserted nothing), the re-read is next
            }
            return r;
        };
        doAnswer(afterLock).when(cartRepository).lockByUserId(anyLong());
        doAnswer(afterInsert).when(cartRepository).insertIfAbsent(anyLong(), any());

        ExecutorService ex = Executors.newSingleThreadExecutor(r -> new Thread(r, VANISH_THREAD));
        ExecutorService other = Executors.newSingleThreadExecutor();
        long[] line = new long[1];
        try {
            // update / remove target the line of the cart that is about to be created and deleted under them
            Callable<MvcResult> request = switch (writer) {
                case "add" -> () -> addItem(c, p, 1);
                case "populate" -> () -> populate(c, p, 1);
                case "update" -> () -> mvc.perform(as(put("/api/v1/cart/items/update/" + line[0])
                        .contentType("application/json").content("{\"quantity\":2}"), c)).andReturn();
                case "remove" -> () -> mvc.perform(as(delete("/api/v1/cart/items/remove/" + line[0]), c)).andReturn();
                default -> throw new IllegalArgumentException(writer);
            };
            boolean lineWriter = writer.equals("update") || writer.equals("remove");
            if (lineWriter) {
                // the line id must be known before the request starts: reserve it from the sequence
                line[0] = jdbc.queryForObject("SELECT nextval(pg_get_serial_sequence('tenant_acme.cart_items', 'id'))", Long.class);
            }
            Future<MvcResult> fut = ex.submit(request);
            assertThat(in1.await(20, TimeUnit.SECONDS)).as("R parked after finding no cart").isTrue();

            // another request of this customer wins the cart creation and commits (with a line)
            long cartId = jdbc.queryForObject("INSERT INTO tenant_acme.carts (user_id, created_at) VALUES (?, now()) RETURNING id",
                    Long.class, c.id());
            jdbc.update("INSERT INTO tenant_acme.cart_items (id, cart_id, product_id, quantity, created_at, amount, currency_code) "
                    + "VALUES (?, ?, ?, 1, now(), 0, 'GHS')", lineWriter ? line[0]
                    : jdbc.queryForObject("SELECT nextval(pg_get_serial_sequence('tenant_acme.cart_items', 'id'))", Long.class), cartId, p);
            out1.countDown();

            // R's insert-if-absent conflicts with that committed cart and inserts nothing (park 2). Update / remove no
            // longer create a cart at all: they never get to park 2 (they already answered)
            for (int i = 0; i < 200 && in2.getCount() > 0 && !fut.isDone(); i++) Thread.sleep(100);
            assertThat(in2.getCount() == 0 || fut.isDone()).as("R parked after its insert, or already answered").isTrue();

            // the clear / settlement deletes that cart and commits
            int del = viaSettlement
                    ? webhookInBackground(g, event("charge.success", o.reference(), o.minor())).get(60, TimeUnit.SECONDS)
                    : other.submit(() -> clear(c).getResponse().getStatus()).get(60, TimeUnit.SECONDS);
            assertThat(del).as(deleter + " answer").isEqualTo(viaSettlement ? 200 : 204);
            out2.countDown();

            MvcResult r = fut.get(60, TimeUnit.SECONDS);
            awaitBackgroundIdle();
            int status = r.getResponse().getStatus();
            String body = r.getResponse().getContentAsString();
            assertThat(status).as(tag + " (was 500: IllegalStateException 'The cart could not be created.') " + body)
                    .isLessThan(500);
            int carts = jdbc.queryForObject("SELECT count(*) FROM tenant_acme.carts WHERE user_id = ?", Integer.class, c.id());
            if (lineWriter) {
                assertThat(status).as(tag + " " + body).isEqualTo(404);
                assertThat(body).contains("Cart item not found");
                assertThat(carts).as("update / remove of a line in a deleted cart creates no cart").isZero();
                // the 404 would roll an inserted cart back anyway, so check the decision itself: never even tried
                assertThat(parked2.get()).as("update / remove never call insert-if-absent").isFalse();
            } else {
                assertThat(status).as(tag + " " + body).isEqualTo(200);
                assertThat(parked2.get()).as("R went through the no-op insert").isTrue();
                assertThat(cartRows(c)).as("a fresh cart with only R's line").hasSize(1)
                        .allSatisfy(row -> {
                            assertThat(((Number) row.get("product_id")).longValue()).isEqualTo(p);
                            assertThat(((Number) row.get("quantity")).intValue()).isEqualTo(1);
                        });
                assertThat(carts).isEqualTo(1);
            }
            if (viaSettlement) assertThat(paymentStatus(o.orderNumber())).isEqualTo("COMPLETED");
        } finally {
            out1.countDown();
            out2.countDown();
            ex.shutdownNow();
            other.shutdownNow();
        }
    }

    // ---- CAT-04 rework F-B: update and remove take the cart row lock (integration, catches QA mutant m5) ----

    /**
     * A clear holds the cart row ({@code FOR UPDATE}, taken by the test on its own connection, as CartRemoval does)
     * when an update-quantity and a remove-line arrive: both must QUEUE on the cart row ({@code FOR NO KEY UPDATE})
     * before touching the line, then, once the clear deleted the lines and the cart and committed, answer 404 and
     * leave no cart behind. Without the cart lock (QA mutant m5: remove without transaction / cart lock) the remove
     * deletes the line under the clear's feet straight away (204).
     */
    @Test
    void updateAndRemoveQueueOnTheCartRowWhileAClearHoldsIt_thenAnswer404_noCartCreated() throws Exception {
        long p = product(acme, "CAT04R1 queue", "10.00", 100);
        Customer c = customer(acme, "cat04r1-queue@acme.test");
        cart(c, p, 1);
        long cartId = jdbc.queryForObject("SELECT id FROM tenant_acme.carts WHERE user_id = ?", Long.class, c.id());
        long line = jdbc.queryForObject("SELECT id FROM tenant_acme.cart_items WHERE cart_id = ?", Long.class, cartId);

        ExecutorService ex = Executors.newFixedThreadPool(2);
        try (Connection holder = jdbc.getDataSource().getConnection()) {
            holder.setAutoCommit(false);
            try (Statement st = holder.createStatement()) {
                st.execute("SET LOCAL statement_timeout = '30s'");
            }
            try (PreparedStatement ps = holder.prepareStatement("SELECT id FROM tenant_acme.carts WHERE user_id = ? FOR UPDATE")) {
                ps.setLong(1, c.id());
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                }
            }
            try {
                Future<MvcResult> update = ex.submit(() -> mvc.perform(as(put("/api/v1/cart/items/update/" + line)
                        .contentType("application/json").content("{\"quantity\":2}"), c)).andReturn());
                Future<MvcResult> remove = ex.submit(() -> mvc.perform(as(delete("/api/v1/cart/items/remove/" + line), c)).andReturn());

                boolean queued = waitersOn("FROM carts WHERE user_id%FOR NO KEY UPDATE", 2);
                boolean updateDone = update.isDone(), removeDone = remove.isDone();
                // the clear goes on: every line, then the cart row, commit
                try (PreparedStatement ps = holder.prepareStatement("DELETE FROM tenant_acme.cart_items WHERE cart_id = ?")) {
                    ps.setLong(1, cartId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = holder.prepareStatement("DELETE FROM tenant_acme.carts WHERE id = ?")) {
                    ps.setLong(1, cartId);
                    ps.executeUpdate();
                }
                holder.commit();

                MvcResult u = update.get(60, TimeUnit.SECONDS);
                MvcResult rm = remove.get(60, TimeUnit.SECONDS);
                assertThat(updateDone).as("update answered while the clear held the cart row: " + u.getResponse().getContentAsString()).isFalse();
                assertThat(removeDone).as("remove answered while the clear held the cart row: " + rm.getResponse().getStatus()).isFalse();
                assertThat(queued).as("update and remove both queued on the cart row lock").isTrue();
                assertThat(u.getResponse().getStatus()).as(u.getResponse().getContentAsString()).isEqualTo(404);
                assertThat(rm.getResponse().getStatus()).as(rm.getResponse().getContentAsString()).isEqualTo(404);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.carts WHERE user_id = ?", Integer.class, c.id()))
                        .as("no cart re-created by update / remove").isZero();
            } finally {
                if (!holder.getAutoCommit()) holder.rollback();
                holder.setAutoCommit(true);
            }
        } finally {
            ex.shutdownNow();
            ex.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    // ---- CAT-04 rework F-D: the settlement empties the cart of the order's user (by id) ----

    @Test
    void settlementEmptiesTheCartOfTheOrdersUser_notOfWhoeverHasThePaymentEmailNow() throws Exception {
        Gateway g = configureGateway(acme);
        long p = product(acme, "CAT04R1 fd", "10.00", 100);
        Placed o = place("cat04r1-fd-buyer@acme.test", p, 1);
        Customer other = customer(acme, "cat04r1-fd-other@acme.test");
        cart(other, p, 2);
        // the payment's e-mail is the checkout-time snapshot; point it at someone else (e.g. the address changed hands)
        jdbc.update("UPDATE tenant_acme.payments SET customer_email = ? WHERE order_id = "
                + "(SELECT id FROM tenant_acme.orders WHERE order_number = ?)", other.email(), o.orderNumber());

        assertThat(webhook(g, event("charge.success", o.reference(), o.minor()))).isEqualTo(200);
        awaitBackgroundIdle();

        assertThat(paymentStatus(o.orderNumber())).isEqualTo("COMPLETED");
        assertThat(cartRows(o.c())).as("the buyer's cart is emptied").isEmpty();
        assertThat(cartRows(other)).as("the cart of whoever has the payment e-mail is untouched").hasSize(1);
    }

    @Test
    void settlementOfAnOrderWithoutAUser_completes_andTouchesNoCart() throws Exception {
        Gateway g = configureGateway(acme);
        long p = product(acme, "CAT04R1 nouser", "10.00", 100);
        Placed o = place("cat04r1-nouser@acme.test", p, 1);
        jdbc.update("UPDATE tenant_acme.orders SET user_id = NULL WHERE order_number = ?", o.orderNumber());

        assertThat(webhook(g, event("charge.success", o.reference(), o.minor()))).isEqualTo(200);
        awaitBackgroundIdle();

        assertThat(paymentStatus(o.orderNumber())).isEqualTo("COMPLETED");
        assertThat(cartRows(o.c())).as("no user on the order: no cart is touched").hasSize(1);
    }

    // ------------------------------------------------------------------------------------------------------------
    // CAT-04b (reviewer probe Q2): the exhaustion contract against the real database, not only CartUtilTest mocks
    // ------------------------------------------------------------------------------------------------------------

    static final String EXHAUST_THREAD = "cat04b-exhaust";

    /**
     * Each time the request calls insert-if-absent, ANOTHER connection first creates and commits the customer's cart
     * (so the insert is a no-op) and deletes it again right after (so the re-read finds nothing). Three rounds in a
     * row: 503 TRY_AGAIN, {@code Retry-After: 1}, a body without internals, exactly 3 insert rounds, and no cart or
     * line left behind. Pins status, code, header and round count (a mutant with 1 or 2 rounds, a 500 or a 409 dies).
     */
    @ParameterizedTest
    @CsvSource({"add", "populate", "get-cart"})
    void aCartThatKeepsVanishingAnswers503TryAgainAfterExactlyThreeRounds(String writer) throws Exception {
        long p = product(acme, "CAT04B " + writer, "10.00", 100);
        Customer c = customer(acme, "cat04b-" + writer + "@acme.test");
        ExecutorService other = Executors.newSingleThreadExecutor();
        java.util.concurrent.atomic.AtomicInteger rounds = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(inv -> {
            if (!Thread.currentThread().getName().equals(EXHAUST_THREAD)) return real(inv);
            rounds.incrementAndGet();
            other.submit(() -> jdbc.update("INSERT INTO tenant_acme.carts (user_id, created_at) VALUES (?, now())", c.id()))
                    .get(20, TimeUnit.SECONDS);
            Object r = real(inv);
            other.submit(() -> jdbc.update("DELETE FROM tenant_acme.carts WHERE user_id = ?", c.id())).get(20, TimeUnit.SECONDS);
            return r;
        }).when(cartRepository).insertIfAbsent(anyLong(), any());
        ExecutorService ex = Executors.newSingleThreadExecutor(r -> new Thread(r, EXHAUST_THREAD));
        try {
            MvcResult r = ex.submit(() -> switch (writer) {
                case "add" -> addItem(c, p, 1);
                case "populate" -> populate(c, p, 1);
                default -> mvc.perform(as(get("/api/v1/cart"), c)).andReturn();
            }).get(60, TimeUnit.SECONDS);
            awaitBackgroundIdle();
            String b = r.getResponse().getContentAsString();
            assertThat(r.getResponse().getStatus()).as(b).isEqualTo(503);
            assertThat(r.getResponse().getHeader("Retry-After")).isEqualTo("1");
            assertThat(body(r).at("/data/code").asText()).isEqualTo("TRY_AGAIN");
            assertThat(b).doesNotContain("Exception").doesNotContain("SQL");
            assertThat(rounds.get()).as("insert rounds").isEqualTo(3);
            assertThat(cartRows(c)).as("no cart and no line left behind").isEmpty();
        } finally {
            ex.shutdownNow();
            other.shutdownNow();
        }
    }
}
