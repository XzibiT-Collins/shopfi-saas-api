package com.example.perfume_budget.checkout;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API-05: concurrent checkouts of the SAME product. Before the fix two checkouts of one product read the same
 * inventory layers, both decremented them and the second commit failed on {@code Product.@Version} with a 500
 * (ObjectOptimisticLockingFailureException). Now every stock writer takes the product rows ({@code FOR NO KEY UPDATE},
 * id order) right before it reads the layers, and checkout does that AFTER the Paystack call, so the lock is held only
 * for the short reservation.
 */
class SameProductCheckoutConcurrencyIntegrationTest extends AbstractCheckoutIntegrationTest {

    /** A finished checkout: HTTP status and body. */
    private record Answer(int status, String body) {
        JsonNode json(com.fasterxml.jackson.databind.ObjectMapper m) throws Exception {
            return m.readTree(body);
        }
    }

    private List<Answer> concurrently(List<Callable<MvcResult>> calls) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<MvcResult>> fs = new ArrayList<>();
            for (Callable<MvcResult> c : calls) {
                fs.add(ex.submit(() -> {
                    go.await();
                    return c.call();
                }));
            }
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

    private static long count(List<Answer> as, int status) {
        return as.stream().filter(a -> a.status() == status).count();
    }

    private int productInt(long productId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM tenant_acme.products WHERE id = ?", Integer.class, productId);
    }

    private long productVersion(long productId) {
        return jdbc.queryForObject("SELECT version FROM tenant_acme.products WHERE id = ?", Long.class, productId);
    }

    private int liveOrders() {
        return jdbc.queryForObject("SELECT count(*) FROM tenant_acme.orders WHERE status = 'INITIATED'", Integer.class);
    }

    private void awaitStatus(String orderNumber, String status) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && !status.equals(orderStatus(acme, orderNumber))) {
            Thread.sleep(50);
        }
        assertThat(orderStatus(acme, orderNumber)).isEqualTo(status);
    }

    // ---- N customers, one product, stock K < N

    @Test
    void stockKForNCustomers_exactlyKOrders_restCleanOutOfStock_noLostUpdates() throws Exception {
        long p = product(acme, "Hot", "100.00", 3);
        initiateDelayMillis = 150; // keep checkouts overlapping
        List<Customer> cs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Customer c = customer(acme, "k" + i + "@acme.test");
            cart(c, p, 1);
            cs.add(c);
        }
        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (Customer c : cs) calls.add(() -> checkout(c, null, null));

        List<Answer> as = concurrently(calls);

        assertThat(count(as, 500)).as(as.toString()).isZero();
        assertThat(count(as, 200)).as(as.toString()).isEqualTo(3);
        assertThat(count(as, 400)).as(as.toString()).isEqualTo(5);
        as.stream().filter(a -> a.status() == 400)
                .forEach(a -> assertThat(a.body()).contains("Insufficient stock for: Hot"));
        assertThat(liveOrders()).isEqualTo(3);
        assertThat(count(acme, "orders")).as("losers left no order behind").isEqualTo(3);
        assertThat(layerRemaining(acme, p)).isZero();
        assertThat(reservedQty(acme, p)).isEqualTo(3);
        assertThat(productInt(p, "stock_quantity")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_acme.inventory_layers WHERE product_id = ?",
                Integer.class, p)).as("the legacy balance is bootstrapped once").isEqualTo(1);

        // pay all three: soldCount and stock stay right
        Gateway g = configureGateway(acme);
        for (Answer a : as) {
            if (a.status() != 200) continue;
            JsonNode b = a.json(json);
            String orderNumber = b.at("/data/orderNumber").asText();
            long minor = jdbc.queryForObject("SELECT total_amount * 100 FROM tenant_acme.orders WHERE order_number = ?",
                    BigDecimal.class, orderNumber).longValue();
            assertThat(webhook(g, event("charge.success", b.at("/data/data/reference").asText(), minor))).isEqualTo(200);
            awaitStatus(orderNumber, "COMPLETED");
        }
        assertThat(productInt(p, "sold_count")).isEqualTo(3);
        assertThat(productInt(p, "stock_quantity")).isZero();
        assertThat(reservedQty(acme, p)).isZero();
    }

    @Test
    void plentyOfStock_everyConcurrentCheckoutSucceeds_stockAndVersionExact() throws Exception {
        long p = product(acme, "Plenty", "100.00", 100);
        initiateDelayMillis = 100;
        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Customer c = customer(acme, "pl" + i + "@acme.test");
            cart(c, p, 2);
            calls.add(() -> checkout(c, null, null));
        }
        long before = productVersion(p);

        List<Answer> as = concurrently(calls);

        assertThat(count(as, 200)).as(as.toString()).isEqualTo(8);
        assertThat(layerRemaining(acme, p)).isEqualTo(84);
        assertThat(productInt(p, "stock_quantity")).isEqualTo(84);
        assertThat(reservedQty(acme, p)).isEqualTo(16);
        assertThat(initiateCalls.get()).isEqualTo(8);
        // one stock write per checkout (the first one also bootstraps the legacy layer): no lost update, no blind bump
        assertThat(productVersion(p) - before).isBetween(8L, 9L);
    }

    @Test
    void counterColumnsWrittenByOthersAreNotOverwrittenByTheStockRefresh() throws Exception {
        long p = product(acme, "Counted", "100.00", 50);
        jdbc.update("UPDATE tenant_acme.products SET view_count = 7, add_to_cart_count = 4, sold_count = 2 WHERE id = ?", p);
        Customer c = customer(acme, "cnt@acme.test");
        cart(c, p, 1);
        assertThat(checkout(c, null, null).getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT view_count FROM tenant_acme.products WHERE id = ?", Long.class, p)).isEqualTo(7L);
        assertThat(jdbc.queryForObject("SELECT add_to_cart_count FROM tenant_acme.products WHERE id = ?", Long.class, p)).isEqualTo(4L);
        assertThat(productInt(p, "sold_count")).isEqualTo(2);
        assertThat(productInt(p, "stock_quantity")).isEqualTo(49);
    }

    // ---- multi-product carts in opposite orders

    @Test
    void multiProductCartsInOppositeOrders_noDeadlock_noConflict() throws Exception {
        long p1 = product(acme, "Alpha", "50.00", 100);
        long p2 = product(acme, "Beta", "60.00", 100);
        long p3 = product(acme, "Gamma", "70.00", 100);
        initiateDelayMillis = 50;
        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            Customer c = customer(acme, "mp" + i + "@acme.test");
            long[] order = switch (i % 3) {
                case 0 -> new long[]{p1, p2, p3};
                case 1 -> new long[]{p3, p2, p1};
                default -> new long[]{p2, p3, p1};
            };
            for (long pid : order) cart(c, pid, 1);
            calls.add(() -> checkout(c, null, null));
        }

        List<Answer> as = concurrently(calls);

        assertThat(count(as, 200)).as(as.toString()).isEqualTo(9);
        for (long pid : new long[]{p1, p2, p3}) {
            assertThat(layerRemaining(acme, pid)).isEqualTo(91);
            assertThat(productInt(pid, "stock_quantity")).isEqualTo(91);
            assertThat(reservedQty(acme, pid)).isEqualTo(9);
        }
    }

    @Test
    void productsAreLockedInIdOrderWhateverTheCartOrder() throws Exception {
        long low = product(acme, "Low", "50.00", 10);
        long high = product(acme, "High", "60.00", 10);
        Customer c = customer(acme, "ord@acme.test");
        cart(c, high, 1); // cart (and order item) order is high, low
        cart(c, low, 1);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try (Connection conn = jdbc.getDataSource().getConnection(); Statement st = conn.createStatement()) {
            conn.setAutoCommit(false);
            st.execute("SELECT id FROM tenant_acme.products WHERE id = " + low + " FOR NO KEY UPDATE");
            Future<MvcResult> f = ex.submit(() -> checkout(c, null, null));
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline
                    && jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Integer.class) == 0) {
                Thread.sleep(25);
            }
            assertThat(f.isDone()).as("checkout waits for the lower id").isFalse();
            // the higher id is still free: checkout did not take it before waiting for the lower one
            try (Connection probe = jdbc.getDataSource().getConnection(); Statement ps = probe.createStatement()) {
                probe.setAutoCommit(false);
                ps.execute("SELECT id FROM tenant_acme.products WHERE id = " + high + " FOR NO KEY UPDATE NOWAIT");
                probe.rollback();
            }
            conn.commit();
            assertThat(f.get(30, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        } finally {
            ex.shutdownNow();
        }
        assertThat(layerRemaining(acme, low)).isEqualTo(9);
        assertThat(layerRemaining(acme, high)).isEqualTo(9);
    }

    // ---- the Paystack call is outside the product lock

    @Test
    void sameProductCheckoutsAreNotSerialisedBehindThePaystackCall() throws Exception {
        long p = product(acme, "Slow", "100.00", 100);
        initiateDelayMillis = 1_500;
        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Customer c = customer(acme, "sl" + i + "@acme.test");
            cart(c, p, 1);
            calls.add(() -> checkout(c, null, null));
        }
        long t0 = System.nanoTime();
        List<Answer> as = concurrently(calls);
        long millis = (System.nanoTime() - t0) / 1_000_000;

        assertThat(count(as, 200)).as(as.toString()).isEqualTo(4);
        // serialised behind Paystack this would take >= 4 x 1.5 s
        assertThat(millis).isLessThan(4_500);
        assertThat(layerRemaining(acme, p)).isEqualTo(96);
    }

    @Test
    void outOfStockFoundBeforePaystackNeverCallsIt() throws Exception {
        long p = product(acme, "Gone", "100.00", 1);
        Customer a = customer(acme, "g1@acme.test");
        cart(a, p, 1);
        assertThat(checkout(a, null, null).getResponse().getStatus()).isEqualTo(200);
        Customer b = customer(acme, "g2@acme.test");
        cart(b, p, 1);
        MvcResult r = checkout(b, null, null);
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).contains("Insufficient stock for: Gone");
        assertThat(initiateCalls.get()).as("no Paystack transaction for a cart that cannot be served").isEqualTo(1);
        assertThat(count(acme, "orders")).isEqualTo(1);
    }

    @Test
    void paystackFailureHoldsNoStock() throws Exception {
        long p = product(acme, "Fail", "100.00", 5);
        Customer c = customer(acme, "pf@acme.test");
        cart(c, p, 2);
        org.mockito.Mockito.when(gateway.initiatePaystackTransaction(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new com.example.perfume_budget.exception.PaymentException("down"));
        assertThat(checkout(c, null, null).getResponse().getStatus()).isNotEqualTo(200);
        assertThat(reservedQty(acme, p)).isZero();
        assertThat(count(acme, "orders")).isZero();
        assertThat(productInt(p, "stock_quantity")).isEqualTo(5);
    }

    // ---- idempotent replay under contention

    @Test
    void idempotentReplayUnderSameProductContention_oneOrderAndOnePaystackCallPerKey() throws Exception {
        long p = product(acme, "Keyed", "100.00", 100);
        initiateDelayMillis = 200;
        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Customer c = customer(acme, "kr" + i + "@acme.test");
            cart(c, p, 1);
            String key = "api05-key-" + i;
            calls.add(() -> checkout(c, key, null));
            calls.add(() -> checkout(c, key, null));
        }

        List<Answer> as = concurrently(calls);

        assertThat(count(as, 200)).as(as.toString()).isEqualTo(10);
        assertThat(initiateCalls.get()).as("one Paystack transaction per key").isEqualTo(5);
        assertThat(count(acme, "orders")).isEqualTo(5);
        assertThat(reservedQty(acme, p)).isEqualTo(5);
        Set<String> refs = new HashSet<>();
        int replays = 0;
        for (int i = 0; i < as.size(); i += 2) {
            JsonNode first = as.get(i).json(json);
            JsonNode second = as.get(i + 1).json(json);
            assertThat(first.at("/data/orderNumber").asText()).isEqualTo(second.at("/data/orderNumber").asText());
            assertThat(first.at("/data/data/reference").asText()).isEqualTo(second.at("/data/data/reference").asText());
            refs.add(first.at("/data/data/reference").asText());
            replays += (first.at("/data/idempotentReplay").asBoolean() ? 1 : 0) + (second.at("/data/idempotentReplay").asBoolean() ? 1 : 0);
        }
        assertThat(refs).hasSize(5);
        assertThat(replays).isEqualTo(5);
    }

    // ---- other stock writers on the same product at the same time

    @Test
    void cancelsAndCheckoutsOfTheSameProductInterleave_stockExact() throws Exception {
        long p = product(acme, "Churn", "100.00", 20);
        List<Customer> first = new ArrayList<>();
        List<String> orders = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Customer c = customer(acme, "ch" + i + "@acme.test");
            cart(c, p, 1);
            MvcResult r = checkout(c, null, null);
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            first.add(c);
            orders.add(body(r).at("/data/orderNumber").asText());
        }
        initiateDelayMillis = 50;
        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Customer c = first.get(i);
            String on = orders.get(i);
            calls.add(() -> cancel(c, on));
            Customer n = customer(acme, "chn" + i + "@acme.test");
            cart(n, p, 2);
            calls.add(() -> checkout(n, null, null));
        }

        List<Answer> as = concurrently(calls);

        assertThat(count(as, 200)).as(as.toString()).isEqualTo(8);
        assertThat(reservedQty(acme, p)).isEqualTo(8);
        assertThat(layerRemaining(acme, p)).isEqualTo(12);
        assertThat(productInt(p, "stock_quantity")).isEqualTo(12);
    }
}
