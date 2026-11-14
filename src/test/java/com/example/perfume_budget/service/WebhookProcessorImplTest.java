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
    void chargeSuccessWithoutAmountOrStatusStillApplied() {
        processor.processWebhook(body("charge.success", ""));
        verify(settlement).applySuccess(eq("REF123"), eq("card"), eq(null), eq(null), anyString());
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
}
