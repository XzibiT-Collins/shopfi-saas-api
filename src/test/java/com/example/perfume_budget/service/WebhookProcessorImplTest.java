package com.example.perfume_budget.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** PAY-01 AC5b: event types are handled explicitly; only charge.failed is a terminal failure. */
@ExtendWith(MockitoExtension.class)
class WebhookProcessorImplTest {

    @Mock PaymentSettlementService settlement;
    @InjectMocks WebhookProcessorImpl processor;

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
        processor.retryBackoffMillis = 0;
        when(settlement.applyFailure(any(), any(), any(), any()))
                .thenThrow(deadlock()).thenThrow(couponBusy()).thenReturn(PaymentSettlementService.Outcome.FAILED);
        processor.processWebhook(body("charge.failed", ",\"message\":\"Declined\""));
        verify(settlement, times(3)).applyFailure(eq("REF123"), eq("card"), eq("Declined"), anyString());
    }

    @Test
    void aRevivalHittingASerializationFailureIsRetried() {
        processor.retryBackoffMillis = 0;
        when(settlement.applySuccess(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException(new java.sql.SQLException("could not serialize", "40001")))
                .thenReturn(PaymentSettlementService.Outcome.COMPLETED);
        processor.processWebhook(body("charge.success", ",\"amount\":1250,\"currency\":\"GHS\",\"status\":\"success\""));
        verify(settlement, times(2)).applySuccess(any(), any(), any(), any(), any());
    }

    @Test
    void retriesAreBounded_andTheFailureIsSwallowedAfterTheLastAttempt() {
        processor.retryBackoffMillis = 0;
        when(settlement.applyFailure(any(), any(), any(), any())).thenThrow(deadlock());
        processor.processWebhook(body("charge.failed", ""));
        verify(settlement, times(WebhookProcessorImpl.ATTEMPTS)).applyFailure(any(), any(), any(), any());
    }

    @Test
    void nonTransientFailuresAreNotRetried() {
        processor.retryBackoffMillis = 0;
        when(settlement.applyFailure(any(), any(), any(), any())).thenThrow(new IllegalStateException("bug"));
        processor.processWebhook(body("charge.failed", ""));
        when(settlement.applySuccess(any(), any(), any(), any(), any()))
                .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException("Product", 1L));
        processor.processWebhook(body("charge.success", ",\"amount\":1250,\"currency\":\"GHS\""));
        verify(settlement, times(1)).applyFailure(any(), any(), any(), any());
        verify(settlement, times(1)).applySuccess(any(), any(), any(), any(), any());
    }

    @Test
    void theBackOffGrowsWithTheAttempt() throws Exception {
        processor.retryBackoffMillis = 40;
        when(settlement.applyFailure(any(), any(), any(), any())).thenThrow(couponBusy());
        long t0 = System.nanoTime();
        processor.processWebhook(body("charge.failed", ""));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        // 40 ms after attempt 1, 80 ms after attempt 2, nothing after the last
        org.assertj.core.api.Assertions.assertThat(ms).isGreaterThanOrEqualTo(120);
    }
}
