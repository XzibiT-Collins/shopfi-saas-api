package com.example.perfume_budget.checkout;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CAT-03 (CAT-02 review low 2): the FIRST cart writes of a customer without a cart row, fired concurrently (two tabs, a
 * double-fired populate after sign-in). Before the fix every request saw "no cart", every request inserted one and all
 * but one failed on {@code uc_carts_user} with a 409 (populate even outside its per-item catch). Now the cart is created
 * with {@code INSERT ... ON CONFLICT (user_id) DO NOTHING} and re-read, and add-item works under the cart row lock, so
 * every request succeeds, they share one cart and no quantity is lost.
 */
class FirstCartConcurrencyIntegrationTest extends AbstractCheckoutIntegrationTest {

    static final int THREADS = 12;
    static final int ROUNDS = 3;

    private record Answer(int status, String body) {}

    /** Runs the calls on their own threads, all released by one latch; every wait is bounded. */
    private List<Answer> concurrently(List<Callable<MvcResult>> calls) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch ready = new CountDownLatch(calls.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<MvcResult>> fs = new ArrayList<>();
            for (Callable<MvcResult> c : calls) {
                fs.add(ex.submit(() -> {
                    ready.countDown();
                    if (!go.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("start latch timed out");
                    return c.call();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).as("all threads parked at the start line").isTrue();
            go.countDown();
            List<Answer> out = new ArrayList<>();
            for (Future<MvcResult> f : fs) {
                MvcResult r = f.get(90, TimeUnit.SECONDS);
                out.add(new Answer(r.getResponse().getStatus(), r.getResponse().getContentAsString()));
            }
            return out;
        } finally {
            ex.shutdownNow();
        }
    }

    private MvcResult addItem(Customer c, long productId, int qty) throws Exception {
        return mvc.perform(as(post("/api/v1/cart/items/add-item").contentType("application/json")
                .content("{\"productId\":" + productId + ",\"quantity\":" + qty + "}"), c)).andReturn();
    }

    private MvcResult populate(Customer c, Map<Long, Integer> items) throws Exception {
        StringBuilder sb = new StringBuilder("{\"cartItems\":[");
        items.forEach((p, q) -> sb.append(sb.charAt(sb.length() - 1) == '[' ? "" : ",")
                .append("{\"productId\":").append(p).append(",\"quantity\":").append(q).append('}'));
        sb.append("]}");
        return mvc.perform(as(post("/api/v1/cart/populate").contentType("application/json").content(sb.toString()), c)).andReturn();
    }

    private int carts(Customer c) {
        return jdbc.queryForObject("SELECT count(*) FROM tenant_acme.carts WHERE user_id = ?", Integer.class, c.id());
    }

    private Map<Long, Integer> lines(Customer c) {
        Map<Long, Integer> out = new java.util.TreeMap<>();
        jdbc.query("SELECT ci.product_id, ci.quantity FROM tenant_acme.cart_items ci JOIN tenant_acme.carts ca ON ca.id = ci.cart_id "
                + "WHERE ca.user_id = ?", rs -> {
            out.put(rs.getLong(1), rs.getInt(2));
        }, c.id());
        return out;
    }

    private static void allOk(List<Answer> as) {
        assertThat(as).as("no 409 / 5xx / partial: " + as).allSatisfy(a -> assertThat(a.status()).isEqualTo(200));
    }

    @Test
    void concurrentFirstAddItem_allSucceed_oneCart_quantitiesSummed() throws Exception {
        long p = product(acme, "Race Add Scent", "10.00", 1000);
        for (int round = 0; round < ROUNDS; round++) {
            Customer c = customer(acme, "race-add-" + round + "@acme.test");
            assertThat(carts(c)).isZero();
            List<Callable<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) calls.add(() -> addItem(c, p, 1));

            List<Answer> as = concurrently(calls);

            allOk(as);
            for (Answer a : as) {
                assertThat(json.readTree(a.body()).at("/data/productName").asText()).isEqualTo("Race Add Scent");
            }
            assertThat(carts(c)).as("exactly one cart row").isEqualTo(1);
            assertThat(lines(c)).as("one line, every add counted").containsExactly(Map.entry(p, THREADS));
        }
    }

    @Test
    void concurrentFirstPopulate_allSucceed_oneCart_itemsCorrect() throws Exception {
        long p1 = product(acme, "Race Pop One", "10.00", 1000);
        long p2 = product(acme, "Race Pop Two", "12.00", 1000);
        Map<Long, Integer> payload = new java.util.LinkedHashMap<>();
        payload.put(p1, 1);
        payload.put(p2, 2);
        for (int round = 0; round < ROUNDS; round++) {
            Customer c = customer(acme, "race-pop-" + round + "@acme.test");
            assertThat(carts(c)).isZero();
            List<Callable<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) calls.add(() -> populate(c, payload));

            List<Answer> as = concurrently(calls);

            allOk(as);
            for (Answer a : as) {
                JsonNode items = json.readTree(a.body()).at("/data/cartItems");
                assertThat(items.isArray()).as(a.body()).isTrue();
                List<String> names = new ArrayList<>();
                items.forEach(i -> names.add(i.get("productName").asText()));
                assertThat(names).as("each answer is the shared cart").containsExactlyInAnyOrder("Race Pop One", "Race Pop Two");
            }
            assertThat(carts(c)).as("exactly one cart row").isEqualTo(1);
            assertThat(lines(c)).as("every populate counted once per item")
                    .containsExactly(Map.entry(Math.min(p1, p2), p1 < p2 ? THREADS : 2 * THREADS),
                            Map.entry(Math.max(p1, p2), p1 < p2 ? 2 * THREADS : THREADS));
        }
    }

    @Test
    void concurrentFirstAddItemOfDifferentProducts_oneCartWithEveryLine() throws Exception {
        List<Long> ps = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) ps.add(product(acme, "Race Mix " + i, "5.00", 50));
        Customer c = customer(acme, "race-mix@acme.test");
        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (long p : ps) calls.add(() -> addItem(c, p, 3));

        List<Answer> as = concurrently(calls);

        allOk(as);
        assertThat(carts(c)).isEqualTo(1);
        Map<Long, Integer> expected = new java.util.TreeMap<>();
        ps.forEach(p -> expected.put(p, 3));
        assertThat(lines(c)).isEqualTo(expected);
    }
}
