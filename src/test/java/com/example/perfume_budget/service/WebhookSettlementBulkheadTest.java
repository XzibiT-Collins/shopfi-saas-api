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
    void theProductionDefaultsAre3PermitsAndAShortWait() throws Exception {
        Parameter[] ps = WebhookSettlementBulkhead.class.getConstructor(int.class, long.class).getParameters();
        assertThat(ps[0].getAnnotation(Value.class).value()).isEqualTo("${shopfi.payments.webhook.max-concurrent-settlements:3}");
        assertThat(ps[1].getAnnotation(Value.class).value()).isEqualTo("${shopfi.payments.webhook.permit-wait-ms:50}");
        assertThat(WebhookSettlementBulkhead.MAX_WAIT_MILLIS).isLessThanOrEqualTo(100);
    }
}
