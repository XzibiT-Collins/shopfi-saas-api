package com.example.perfume_budget.checkout;

import com.example.perfume_budget.service.WebhookSettlementBulkhead;
import com.example.perfume_budget.support.PostgresTestDatabase;
import com.example.perfume_budget.support.TestTenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * EVT-01d (1): the webhook bulkhead's permits are shared by all tenants, and a tenant knows its own Paystack secret, so
 * it can flood SIGNED webhooks. Before, acme's flood of settlements stuck behind a held product row kept all 3 permits
 * busy (each waiting up to its lock limit) and bravo's settlement answered 503 for as long as the flood lasted. Now one
 * tenant holds at most 1 of the 3 permits at a time: acme's flood holds 1 (the rest of it answers 503 fast without
 * taking a shared permit), and bravo's settlements keep going through, 200 and committed, while the flood goes on.
 *
 * <p>Runs with the PRODUCTION limits (3 permits, 1 per tenant, 50 ms wait) set on the real bulkhead (the test profile
 * raises them for its single-tenant race tests). The held row is released by the test; every request runs on a pool
 * thread and is awaited with a timeout.
 */
class WebhookTenantFairnessIntegrationTest extends AbstractCheckoutIntegrationTest {

    @Autowired WebhookSettlementBulkhead bulkhead;

    private Object savedPermits;
    private Object savedPerTenant;

    @BeforeEach
    void productionLimits() {
        savedPermits = ReflectionTestUtils.getField(bulkhead, "permits");
        savedPerTenant = ReflectionTestUtils.getField(bulkhead, "perTenant");
        ReflectionTestUtils.setField(bulkhead, "permits", new Semaphore(3));
        ReflectionTestUtils.setField(bulkhead, "perTenant", 1);
        tenantSlots().clear();
    }

    @AfterEach
    void testProfileLimits() {
        ReflectionTestUtils.setField(bulkhead, "permits", savedPermits);
        ReflectionTestUtils.setField(bulkhead, "perTenant", savedPerTenant);
        tenantSlots().clear();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Semaphore> tenantSlots() {
        return (Map<String, Semaphore>) ReflectionTestUtils.getField(bulkhead, "tenantSlots");
    }

    private String placeOrder(Customer c) throws Exception {
        MvcResult r = checkout(c, null, null);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r).at("/data/orderNumber").asText();
    }

    private long totalMinor(TestTenant t, String on) {
        return jdbc.queryForObject("SELECT total_amount * 100 FROM " + t.schemaName() + ".orders WHERE order_number = ?",
                BigDecimal.class, on).longValue();
    }

    private String reference(TestTenant t, String on) {
        return jdbc.queryForObject("SELECT p.provider_payment_reference FROM " + t.schemaName() + ".payments p JOIN "
                + t.schemaName() + ".orders o ON o.id = p.order_id WHERE o.order_number = ?", String.class, on);
    }

    private int send(Gateway g, String body) throws Exception {
        return mvc.perform(post("/api/v1/webhook/" + g.token()).contentType("application/json")
                .header("X-Paystack-Signature", hmac512(body, g.secret())).content(body)).andReturn().getResponse().getStatus();
    }

    private String paidOrderBody(TestTenant t, String email, long product) throws Exception {
        Customer c = customer(t, email);
        cart(c, product, 1);
        String on = placeOrder(c);
        return on + "\n" + event("charge.success", reference(t, on), totalMinor(t, on));
    }

    @Test
    void aTenantFloodingSignedWebhooks_holdsOnePermit_andAnotherTenantsSettlementsStillGoThrough() throws Exception {
        Gateway ga = configureGateway(acme);
        Gateway gb = configureGateway(bravo);
        long hot = product(acme, "FloodHot", "50.00", 100);
        long bp = product(bravo, "BravoItem", "40.00", 100);
        int n = 12;
        List<String> acmeOrders = new ArrayList<>();
        List<String> acmeBodies = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String[] ob = paidOrderBody(acme, "flood" + i + "@acme.test", hot).split("\n", 2);
            acmeOrders.add(ob[0]);
            acmeBodies.add(ob[1]);
        }
        List<String> bravoOrders = new ArrayList<>();
        List<String> bravoBodies = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String[] ob = paidOrderBody(bravo, "fair" + i + "@bravo.test", bp).split("\n", 2);
            bravoOrders.add(ob[0]);
            bravoBodies.add(ob[1]);
        }
        awaitBackgroundIdle();

        ExecutorService pool = Executors.newFixedThreadPool(n + 4);
        AtomicBoolean flooding = new AtomicBoolean(true);
        Map<Integer, AtomicInteger> acmeStatuses = new ConcurrentHashMap<>();
        AtomicInteger maxAcmeInPermits = new AtomicInteger();
        List<Future<?>> flood = new ArrayList<>();
        Connection holder = PostgresTestDatabase.connect();
        try {
            holder.setAutoCommit(false);
            try (Statement st = holder.createStatement()) {
                st.execute("SET lock_timeout = '10s'");
                st.execute("SELECT id FROM tenant_acme.products WHERE id = " + hot + " FOR NO KEY UPDATE");
            }
            // acme floods: every one of its settlements gets stuck behind the held product row; keeps re-sending
            CountDownLatch started = new CountDownLatch(n);
            for (int i = 0; i < n; i++) {
                String b = acmeBodies.get(i);
                flood.add(pool.submit(() -> {
                    started.countDown();
                    while (flooding.get()) {
                        int st = send(ga, b);
                        acmeStatuses.computeIfAbsent(st, k -> new AtomicInteger()).incrementAndGet();
                        maxAcmeInPermits.accumulateAndGet(3 - bulkhead.availablePermits(), Math::max);
                        if (st == 200) {
                            return null;
                        }
                    }
                    return null;
                }));
            }
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            long end = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < end && acmeStatuses.getOrDefault(503, new AtomicInteger()).get() < 3 * n) {
                Thread.sleep(10);
            }
            assertThat(acmeStatuses.get(503)).as("acme's excess webhooks answered 503").isNotNull();

            // bravo settles during the flood, one after the other: each 200, committed, quickly
            for (int i = 0; i < bravoBodies.size(); i++) {
                long t0 = System.nanoTime();
                String b = bravoBodies.get(i);
                int st = pool.submit(() -> send(gb, b)).get(10, TimeUnit.SECONDS);
                long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
                System.out.println("EVT01D fairness: bravo settlement " + i + " during acme's flood -> " + st + " in " + ms + " ms");
                assertThat(st).as("bravo is not starved by acme's flood").isEqualTo(200);
                assertThat(orderStatus(bravo, bravoOrders.get(i))).isEqualTo("COMPLETED");
                assertThat(ms).isLessThan(3000);
            }
            // checked only now: the bravo assertions above are the behaviour, these the mechanism
            assertThat(bulkhead.availableTenantSlots("tenant_acme")).as("acme holds its one slot").isZero();
            assertThat(bulkhead.availablePermits()).as("acme holds exactly one shared permit").isEqualTo(2);
            assertThat(maxAcmeInPermits.get()).as("acme never held more than one shared permit").isLessThanOrEqualTo(2);
            // the flood never applied anything while the row was held
            for (String on : acmeOrders) {
                assertThat(orderStatus(acme, on)).isNotEqualTo("COMPLETED");
            }
        } finally {
            flooding.set(false);
            holder.rollback();
            holder.close();
        }
        for (Future<?> f : flood) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        System.out.println("EVT01D fairness: acme statuses during the flood " + acmeStatuses);
        assertThat(bulkhead.availablePermits()).isEqualTo(3);
        assertThat(bulkhead.availableTenantSlots("tenant_acme")).isEqualTo(1);
        assertThat(bulkhead.availableTenantSlots("tenant_bravo")).isEqualTo(1);
        awaitBackgroundIdle();
        // with the row free, acme's redeliveries settle one by one
        for (int i = 0; i < n; i++) {
            if (!"COMPLETED".equals(orderStatus(acme, acmeOrders.get(i)))) {
                assertThat(send(ga, acmeBodies.get(i))).isEqualTo(200);
            }
            assertThat(orderStatus(acme, acmeOrders.get(i))).isEqualTo("COMPLETED");
        }
    }

    @Test
    void whenNoTenantFloods_twoTenantsSettleSideBySide() throws Exception {
        Gateway ga = configureGateway(acme);
        Gateway gb = configureGateway(bravo);
        String[] a = paidOrderBody(acme, "side@acme.test", product(acme, "SideA", "10.00", 5)).split("\n", 2);
        String[] b = paidOrderBody(bravo, "side@bravo.test", product(bravo, "SideB", "10.00", 5)).split("\n", 2);
        awaitBackgroundIdle();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> fa = pool.submit(() -> send(ga, a[1]));
            Future<Integer> fb = pool.submit(() -> send(gb, b[1]));
            assertThat(fa.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(fb.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(orderStatus(acme, a[0])).isEqualTo("COMPLETED");
        assertThat(orderStatus(bravo, b[0])).isEqualTo("COMPLETED");
        assertThat(bulkhead.availablePermits()).isEqualTo(3);
    }
}
