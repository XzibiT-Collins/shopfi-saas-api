package com.example.perfume_budget.service;

import com.example.perfume_budget.service.WebhookSettlementBulkhead.WebhookBusyException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Parameter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** EVT-01c rework 1: the webhook settlement bulkhead (permit before any connection, 503 fast, always released). */
class WebhookSettlementBulkheadTest {

    @Test
    void thePermitIsReleasedWhenTheSettlementThrows_runtimeExceptionOrError() {
        WebhookSettlementBulkhead b = new WebhookSettlementBulkhead(2, 0);
        assertThatThrownBy(() -> b.call(() -> { throw new IllegalStateException("settlement failed"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> b.call(() -> { throw new AssertionError("an Error"); }))
                .isInstanceOf(AssertionError.class);
        assertThat(b.availablePermits()).isEqualTo(2);
        // and the permits are really usable again: two more settlements go through one after the other, many times
        for (int i = 0; i < 10; i++) {
            assertThat(b.call(() -> "ok")).isEqualTo("ok");
        }
        assertThat(b.availablePermits()).isEqualTo(2);
    }

    @Test
    void noFreePermit_isRefusedFast_withoutRunningTheSettlement_andTheHolderKeepsItsPermit() throws Exception {
        WebhookSettlementBulkhead b = new WebhookSettlementBulkhead(1, 50);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> holder = pool.submit(() -> b.call(() -> {
                inside.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "held";
            }));
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            AtomicInteger ran = new AtomicInteger();
            long t0 = System.nanoTime();
            // on another thread with a timeout: a blocking acquire() must fail this test, not hang it
            Future<Throwable> refused = pool.submit(() -> {
                try {
                    b.call(ran::incrementAndGet);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            assertThat(refused.get(2, TimeUnit.SECONDS)).isInstanceOf(WebhookBusyException.class);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).as("refused within the short wait").isLessThan(1000);
            assertThat(ran).hasValue(0);
            release.countDown();
            assertThat(holder.get(10, TimeUnit.SECONDS)).isEqualTo("held");
            assertThat(b.availablePermits()).isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void theRefusalCarriesNoDetail() {
        WebhookBusyException e = new WebhookBusyException();
        assertThat(e.getCause()).isNull();
        assertThat(e.getStackTrace()).isEmpty();
    }

    @Test
    void theWaitIsCappedAt100ms() throws Exception {
        WebhookSettlementBulkhead b = new WebhookSettlementBulkhead(1, 60_000);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch inside = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> b.call(() -> {
                inside.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            long t0 = System.nanoTime();
            Future<Throwable> refused = pool.submit(() -> {
                try {
                    b.call(() -> null);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            assertThat(refused.get(5, TimeUnit.SECONDS)).isInstanceOf(WebhookBusyException.class);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(1000);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void itIsEnteredOutsideATransaction_soNoConnectionIsHeldWhileWaiting() {
        WebhookSettlementBulkhead b = new WebhookSettlementBulkhead(1, 0);
        AtomicInteger ran = new AtomicInteger();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> b.call(ran::incrementAndGet)).isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThat(ran).hasValue(0);
        assertThat(b.availablePermits()).isEqualTo(1);
    }

    @Test
    void theProductionDefaultsAre3Permits_1PerTenant_andAShortWait() throws Exception {
        java.lang.reflect.Constructor<WebhookSettlementBulkhead> c =
                WebhookSettlementBulkhead.class.getConstructor(int.class, int.class, long.class);
        assertThat(c.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class))
                .as("Spring uses the 3-argument constructor").isTrue();
        Parameter[] ps = c.getParameters();
        assertThat(ps[0].getAnnotation(Value.class).value()).isEqualTo("${shopfi.payments.webhook.max-concurrent-settlements:3}");
        assertThat(ps[1].getAnnotation(Value.class).value())
                .isEqualTo("${shopfi.payments.webhook.max-concurrent-settlements-per-tenant:1}");
        assertThat(ps[2].getAnnotation(Value.class).value()).isEqualTo("${shopfi.payments.webhook.permit-wait-ms:50}");
        assertThat(WebhookSettlementBulkhead.MAX_WAIT_MILLIS).isLessThanOrEqualTo(100);
    }

    // ---- EVT-01d (1): per-tenant fairness

    private static <T> java.util.concurrent.Callable<T> asTenant(String schema, java.util.concurrent.Callable<T> body) {
        return () -> {
            com.example.perfume_budget.tenancy.TenantContext.set(
                    com.example.perfume_budget.tenancy.TenantIdentity.tenant(schema.replace("tenant_", ""), schema));
            try {
                return body.call();
            } finally {
                com.example.perfume_budget.tenancy.TenantContext.clear();
            }
        };
    }

    private static Throwable attempt(WebhookSettlementBulkhead b, AtomicInteger ran) {
        try {
            b.call(ran::incrementAndGet);
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    @Test
    void aTenantHoldingItsSlot_isRefusedASecondOneFast_withoutTakingASharedPermit_whileAnotherTenantGetsOne() throws Exception {
        WebhookSettlementBulkhead b = new WebhookSettlementBulkhead(3, 1, 50);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Future<String> holder = pool.submit(asTenant("tenant_acme", () -> b.call(() -> {
                inside.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "held";
            })));
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(b.availablePermits()).isEqualTo(2);
            assertThat(b.availableTenantSlots("tenant_acme")).isZero();

            AtomicInteger ran = new AtomicInteger();
            long t0 = System.nanoTime();
            assertThat(pool.submit(asTenant("tenant_acme", () -> attempt(b, ran))).get(2, TimeUnit.SECONDS))
                    .isInstanceOf(WebhookBusyException.class);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(1000);
            assertThat(ran).hasValue(0);
            assertThat(b.availablePermits()).as("the refused one never took a shared permit").isEqualTo(2);

            // another tenant settles at once, twice in a row (its slot is given back)
            assertThat(pool.submit(asTenant("tenant_bravo", () -> attempt(b, ran))).get(2, TimeUnit.SECONDS)).isNull();
            assertThat(pool.submit(asTenant("tenant_bravo", () -> attempt(b, ran))).get(2, TimeUnit.SECONDS)).isNull();
            assertThat(ran).hasValue(2);
            assertThat(b.availableTenantSlots("tenant_bravo")).isEqualTo(1);

            release.countDown();
            assertThat(holder.get(10, TimeUnit.SECONDS)).isEqualTo("held");
            assertThat(b.availablePermits()).isEqualTo(3);
            assertThat(b.availableTenantSlots("tenant_acme")).isEqualTo(1);
            // and acme can settle again
            assertThat(pool.submit(asTenant("tenant_acme", () -> attempt(b, ran))).get(2, TimeUnit.SECONDS)).isNull();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void aFloodingTenantNeverHoldsMoreThanItsSlots_soTheOthersKeepTheRestOfThePermits() throws Exception {
        WebhookSettlementBulkhead b = new WebhookSettlementBulkhead(3, 1, 50);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger concurrentAcme = new AtomicInteger();
        AtomicInteger maxAcme = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(24);
        try {
            java.util.List<Future<Throwable>> flood = new java.util.ArrayList<>();
            for (int i = 0; i < 20; i++) {
                flood.add(pool.submit(asTenant("tenant_acme", () -> {
                    try {
                        b.call(() -> {
                            maxAcme.accumulateAndGet(concurrentAcme.incrementAndGet(), Math::max);
                            try {
                                release.await(30, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            concurrentAcme.decrementAndGet();
                            return null;
                        });
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                })));
            }
            long refused = 0;
            for (Future<Throwable> f : flood) {
                try {
                    if (f.get(2, TimeUnit.SECONDS) instanceof WebhookBusyException) refused++;
                } catch (java.util.concurrent.TimeoutException holdingOne) {
                    // the one that holds acme's slot
                }
            }
            assertThat(refused).isEqualTo(19);
            assertThat(maxAcme).hasValue(1);
            assertThat(b.availablePermits()).isEqualTo(2);
            AtomicInteger ran = new AtomicInteger();
            assertThat(pool.submit(asTenant("tenant_bravo", () -> attempt(b, ran))).get(2, TimeUnit.SECONDS)).isNull();
            assertThat(pool.submit(asTenant("tenant_charlie", () -> attempt(b, ran))).get(2, TimeUnit.SECONDS)).isNull();
            assertThat(ran).hasValue(2);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(b.availablePermits()).isEqualTo(3);
    }

    @Test
    void aSecondWebhookOfTheSameTenantWaitsBrieflyForTheSlot_andRunsWhenItFreesInTime() throws Exception {
        WebhookSettlementBulkhead b = new WebhookSettlementBulkhead(3, 1, 100);
        CountDownLatch inside = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> holder = pool.submit(asTenant("tenant_acme", () -> b.call(() -> {
                inside.countDown();
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            })));
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            AtomicInteger ran = new AtomicInteger();
            assertThat(pool.submit(asTenant("tenant_acme", () -> attempt(b, ran))).get(2, TimeUnit.SECONDS)).isNull();
            assertThat(ran).hasValue(1);
            holder.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void whenTheSharedPermitsAreFull_theTenantSlotIsGivenBack() throws Exception {
        WebhookSettlementBulkhead b = new WebhookSettlementBulkhead(1, 1, 0);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(asTenant("tenant_acme", () -> b.call(() -> {
                inside.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            })));
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            AtomicInteger ran = new AtomicInteger();
            assertThat(pool.submit(asTenant("tenant_bravo", () -> attempt(b, ran))).get(2, TimeUnit.SECONDS))
                    .isInstanceOf(WebhookBusyException.class);
            assertThat(b.availableTenantSlots("tenant_bravo")).as("bravo's slot given back").isEqualTo(1);
            assertThat(ran).hasValue(0);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
