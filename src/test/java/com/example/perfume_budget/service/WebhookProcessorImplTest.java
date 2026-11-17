package com.example.perfume_budget.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** PAY-01 AC5b: event types are handled explicitly; only charge.failed is a terminal failure. */
@ExtendWith(MockitoExtension.class)
class WebhookProcessorImplTest {

    @Mock PaymentSettlementService settlement;
    @org.mockito.Spy TransientRetry retry = new TransientRetry(0);
    @Mock WebhookLockTimeout lockTimeout;
    @org.mockito.Spy WebhookSettlementBulkhead bulkhead = new WebhookSettlementBulkhead(3, 0);
    @InjectMocks WebhookProcessorImpl processor;

    @org.junit.jupiter.api.BeforeEach
    void lockTimeoutPassesThrough() {
        // EVT-01c: each attempt runs inside the bounded-lock-wait transaction (real one in the integration tests)
        lenient().when(lockTimeout.timeoutMillis()).thenReturn(8000L);
        lenient().when(lockTimeout.deadlineMillis()).thenReturn(20_000L);
        lenient().when(lockTimeout.call(anyLong(), any())).thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(1)).get());
    }

    @Test
    void everySettlementAttemptRunsInsideTheBoundedLockWait() {
        processor.processWebhook(body("charge.failed", ",\"message\":\"Declined\""));
        verify(lockTimeout).call(eq(8000L), any());
        verify(bulkhead).call(any());
        org.assertj.core.api.Assertions.assertThat(bulkhead.availablePermits()).isEqualTo(3);
    }

    // ---- EVT-01c rework 1: bulkhead and overall deadline

    private void takeAllPermits(java.util.concurrent.CountDownLatch release, java.util.concurrent.ExecutorService pool)
            throws Exception {
        java.util.concurrent.CountDownLatch in = new java.util.concurrent.CountDownLatch(3);
        for (int i = 0; i < 3; i++) {
            pool.submit(() -> bulkhead.call(() -> {
                in.countDown();
                try {
                    release.await(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
        }
        org.assertj.core.api.Assertions.assertThat(in.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void noFreePermit_theSettlementIsNotStarted_andBusyIsRaisedUnwrapped() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            takeAllPermits(release, pool);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> processor.processWebhook(
                            body("charge.success", ",\"amount\":1250,\"currency\":\"GHS\",\"status\":\"success\"")))
                    .isInstanceOf(WebhookSettlementBulkhead.WebhookBusyException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> processor.processWebhook(body("charge.failed", "")))
                    .isInstanceOf(WebhookSettlementBulkhead.WebhookBusyException.class);
            verifyNoInteractions(settlement);
            verify(lockTimeout, never()).call(anyLong(), any());

            // events that can never apply need no permit: still acknowledged while the bulkhead is full
            processor.processWebhook(body("transfer.success", ""));
            processor.processWebhook("not json");
            processor.processWebhook(body("charge.success", ""));
            verifyNoInteractions(settlement);
        } finally {
            release.countDown();
            pool.shutdown();
            pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        org.assertj.core.api.Assertions.assertThat(bulkhead.availablePermits()).isEqualTo(3);
    }

    @Test
    void thePermitIsReleasedWhenTheSettlementFails() {
        when(settlement.applyFailure(any(), any(), any(), any())).thenThrow(new IllegalStateException("bug"));
        for (int i = 0; i < 5; i++) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> processor.processWebhook(body("charge.failed", "")))
                    .isInstanceOf(WebhookProcessorImpl.WebhookNotAppliedException.class);
        }
        org.assertj.core.api.Assertions.assertThat(bulkhead.availablePermits()).isEqualTo(3);
    }

    private static RuntimeException lockTimedOut() {
        return new org.springframework.dao.PessimisticLockingFailureException("lock timeout",
                new java.sql.SQLException("canceling statement due to lock timeout", "55P03"));
    }

    @Test
    void theDeadlineStopsTheRetries_andCapsEachAttemptsLockTimeoutToTheBudgetLeft() {
        when(lockTimeout.deadlineMillis()).thenReturn(1000L);
        java.util.List<Long> limits = new java.util.concurrent.CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            limits.add(inv.getArgument(0));
            Thread.sleep(800); // the attempt waited for a lock until it timed out
            throw lockTimedOut();
        }).when(lockTimeout).call(anyLong(), any());
        long t0 = System.nanoTime();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> processor.processWebhook(body("charge.failed", "")))
                .isInstanceOf(WebhookProcessorImpl.WebhookNotAppliedException.class);
        long took = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        org.assertj.core.api.Assertions.assertThat(limits).as("no attempt started with < MIN_ATTEMPT_MILLIS left").hasSize(1);
        org.assertj.core.api.Assertions.assertThat(limits.getFirst()).as("capped to the budget, not the 8 s limit")
                .isLessThanOrEqualTo(1000L).isGreaterThan(500L);
        org.assertj.core.api.Assertions.assertThat(took).isLessThan(2000);
        org.assertj.core.api.Assertions.assertThat(bulkhead.availablePermits()).isEqualTo(3);
    }

    @Test
    void aLaterAttemptGetsOnlyTheBudgetLeft() {
        when(lockTimeout.deadlineMillis()).thenReturn(1500L);
        java.util.List<Long> limits = new java.util.concurrent.CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            limits.add(inv.getArgument(0));
            if (limits.size() == 1) {
                Thread.sleep(700);
                throw lockTimedOut();
            }
            return PaymentSettlementService.Outcome.FAILED;
        }).when(lockTimeout).call(anyLong(), any());
        processor.processWebhook(body("charge.failed", ""));
        org.assertj.core.api.Assertions.assertThat(limits).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(limits.get(0)).isLessThanOrEqualTo(1500L).isGreaterThan(1200L);
        org.assertj.core.api.Assertions.assertThat(limits.get(1)).as("what is left of the budget").isLessThanOrEqualTo(800L)
                .isGreaterThanOrEqualTo(WebhookLockTimeout.MIN_ATTEMPT_MILLIS);
    }

    @Test
    void withinTheBudget_theConfiguredLockTimeoutIsUsed_andATimedOutAttemptIsRetried() {
        java.util.List<Long> limits = new java.util.concurrent.CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            limits.add(inv.getArgument(0));
            throw lockTimedOut();
        }).when(lockTimeout).call(anyLong(), any());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> processor.processWebhook(body("charge.failed", "")))
                .isInstanceOf(WebhookProcessorImpl.WebhookNotAppliedException.class);
        org.assertj.core.api.Assertions.assertThat(limits).containsExactly(8000L, 8000L, 8000L);
    }

    private static String body(String event, String extra) {
        return "{\"event\":\"" + event + "\",\"unknownTop\":1,\"data\":{\"reference\":\"REF123\",\"channel\":\"card\"" + extra + "}}";
    }

    @Test
    void chargeSuccessIsApplied() {
        processor.processWebhook(body("charge.success", ",\"amount\":1250,\"currency\":\"GHS\",\"status\":\"success\""));
        verify(settlement).applySuccess(eq("REF123"), eq("card"), eq(new BigDecimal("1250")), eq("GHS"), anyString());
        verifyNoMoreInteractions(settlement);
    }

    @Test
    void chargeSuccessWithoutAmountOrCurrencyFailsClosed() {
        processor.processWebhook(body("charge.success", ""));
        processor.processWebhook(body("charge.success", ",\"amount\":1250"));
        processor.processWebhook(body("charge.success", ",\"currency\":\"GHS\""));
        verifyNoInteractions(settlement);
    }

    @Test
    void chargeSuccessWithNonSuccessStatusIsIgnored() {
        processor.processWebhook(body("charge.success", ",\"status\":\"abandoned\""));
        verifyNoInteractions(settlement);
    }

    @Test
    void chargeFailedIsTheOnlyFailure() {
        processor.processWebhook(body("charge.failed", ",\"message\":\"Declined\""));
        verify(settlement).applyFailure(eq("REF123"), eq("card"), eq("Declined"), anyString());
        verifyNoMoreInteractions(settlement);
    }

    @Test
    void otherEventTypesAreIgnored() {
        for (String e : new String[]{"transfer.success", "refund.processed", "charge.dispute.create", "customeridentification.failed", "invoice.payment_failed", "whatever"}) {
            processor.processWebhook(body(e, ""));
        }
        verifyNoInteractions(settlement);
    }

    @Test
    void malformedOrIncompleteBodiesAreSwallowed() {
        processor.processWebhook("not json");
        processor.processWebhook("{\"event\":\"charge.success\"}");
        processor.processWebhook("{\"event\":\"charge.success\",\"data\":{\"reference\":\" \"}}");
        verify(settlement, never()).applySuccess(any(), any(), any(), any(), any());
    }

    // ---- API-04 rework 2: transient database failures are retried in-process (Paystack already got its 200)

    private static RuntimeException deadlock() {
        return new org.springframework.dao.CannotAcquireLockException("deadlock",
                new java.sql.SQLException("deadlock detected", "40P01"));
    }

    private static RuntimeException couponBusy() {
        return new com.example.perfume_budget.exception.ApiCodeException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, CouponRedemptionService.BUSY, "busy");
    }

    @Test
    void aFailureWebhookHittingADeadlockThenCouponBusyIsRetriedUntilItApplies() {
        when(settlement.applyFailure(any(), any(), any(), any()))
                .thenThrow(deadlock()).thenThrow(couponBusy()).thenReturn(PaymentSettlementService.Outcome.FAILED);
        processor.processWebhook(body("charge.failed", ",\"message\":\"Declined\""));
        verify(settlement, times(3)).applyFailure(eq("REF123"), eq("card"), eq("Declined"), anyString());
    }

    @Test
    void aRevivalHittingASerializationFailureIsRetried() {
        when(settlement.applySuccess(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException(new java.sql.SQLException("could not serialize", "40001")))
                .thenReturn(PaymentSettlementService.Outcome.COMPLETED);
        processor.processWebhook(body("charge.success", ",\"amount\":1250,\"currency\":\"GHS\",\"status\":\"success\""));
        verify(settlement, times(2)).applySuccess(any(), any(), any(), any(), any());
    }

    @Test
    void retriesAreBounded_andTheLastFailureIsRaisedSoTheWebhookIsNotAcknowledged() {
        when(settlement.applyFailure(any(), any(), any(), any())).thenThrow(deadlock());
        // EVT-01c: no longer swallowed; the webhook answers non-2xx and Paystack redelivers the event
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> processor.processWebhook(body("charge.failed", "")))
                .isInstanceOf(WebhookProcessorImpl.WebhookNotAppliedException.class);
        verify(settlement, times(TransientRetry.ATTEMPTS)).applyFailure(any(), any(), any(), any());
    }

    @Test
    void nonTransientFailuresAreNotRetried() {
        when(settlement.applyFailure(any(), any(), any(), any())).thenThrow(new IllegalStateException("bug ob@x.test"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> processor.processWebhook(body("charge.failed", "")))
                .isInstanceOf(WebhookProcessorImpl.WebhookNotAppliedException.class)
                .hasMessageNotContaining("ob@x.test").hasNoCause();
        verify(settlement, times(1)).applyFailure(any(), any(), any(), any());
    }

    @Test
    void anOptimisticConflictOnASharedProductIsRetried() {
        when(settlement.applySuccess(any(), any(), any(), any(), any()))
                .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException("Product", 1L))
                .thenReturn(PaymentSettlementService.Outcome.COMPLETED);
        processor.processWebhook(body("charge.success", ",\"amount\":1250,\"currency\":\"GHS\""));
        verify(settlement, times(2)).applySuccess(any(), any(), any(), any(), any());
    }

    @Test
    void aSettlementThatThrowsIsNotAcknowledged_successAndFailureEvents() {
        when(settlement.applySuccess(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("outbox insert"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> processor.processWebhook(
                        body("charge.success", ",\"amount\":1250,\"currency\":\"GHS\",\"status\":\"success\"")))
                .isInstanceOf(WebhookProcessorImpl.WebhookNotAppliedException.class);
    }

    @Test
    void theProcessorIsNotAsync_theAnswerWaitsForTheSettlement() throws Exception {
        java.lang.reflect.Method m = WebhookProcessorImpl.class.getMethod("processWebhook", String.class);
        org.assertj.core.api.Assertions.assertThat(org.springframework.core.annotation.AnnotatedElementUtils
                .hasAnnotation(m, org.springframework.scheduling.annotation.Async.class)).isFalse();
        org.assertj.core.api.Assertions.assertThat(org.springframework.core.annotation.AnnotatedElementUtils
                .hasAnnotation(WebhookProcessorImpl.class, org.springframework.scheduling.annotation.Async.class)).isFalse();
    }
}
