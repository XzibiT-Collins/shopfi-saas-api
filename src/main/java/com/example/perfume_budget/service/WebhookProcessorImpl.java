package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.payment.request.PaystackWebhook;
import com.example.perfume_budget.dto.payment.request.WebhookData;
import com.example.perfume_budget.service.interfaces.WebhookProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Applies a signature-verified Paystack event. Runs async under the tenant context propagated by the TaskDecorator.
 *
 * <p>Event types are handled explicitly (PAY-01 AC5b):
 * <ul>
 *   <li>{@code charge.success} completes the payment (also a late success on a failed/cancelled-but-unpaid order);</li>
 *   <li>{@code charge.failed} is the only event that marks a payment FAILED;</li>
 *   <li>every other event type is ignored (never a terminal state change).</li>
 * </ul>
 * All state changes go through {@link PaymentSettlementService} (idempotent, order row lock).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookProcessorImpl implements WebhookProcessor {

    static final String EVENT_CHARGE_SUCCESS = "charge.success";
    static final String EVENT_CHARGE_FAILED = "charge.failed";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PaymentSettlementService settlementService;

    @Async("webhookTaskExecutor")
    @Override
    public void processWebhook(String body) {
        try {
            PaystackWebhook payload = MAPPER.readValue(body, PaystackWebhook.class);
            WebhookData data = payload.data();
            String event = payload.event();
            if (event == null || data == null || data.reference() == null || data.reference().isBlank()) {
                log.info("Ignoring webhook without event/reference");
                return;
            }
            String reference = data.reference().trim();

            switch (event) {
                case EVENT_CHARGE_SUCCESS -> {
                    if (data.status() != null && !"success".equalsIgnoreCase(data.status())) {
                        log.info("Ignoring charge.success with non-success status for reference {}", reference);
                        return;
                    }
                    var outcome = settlementService.applySuccess(reference, data.channel(), data.amount(),
                            data.currency(), body);
                    log.info("charge.success for reference {}: {}", reference, outcome);
                }
                case EVENT_CHARGE_FAILED -> {
                    var outcome = settlementService.applyFailure(reference, data.channel(), data.message(), body);
                    log.info("charge.failed for reference {}: {}", reference, outcome);
                }
                default -> log.info("Ignoring unhandled webhook event type for reference {}", reference);
            }
        } catch (Exception e) {
            // Never log the body or the parsed payload: it carries the customer's email and card details.
            log.error("Error processing webhook: {}", e.getClass().getSimpleName());
        }
    }
}
