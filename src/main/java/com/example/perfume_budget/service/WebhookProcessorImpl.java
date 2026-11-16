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
 *
 * <p><b>Transient failures (API-04 rework 2).</b> Paystack has already been answered 200 when this runs, so it will not
 * redeliver. A transient database failure while applying the event (deadlock victim, serialization failure, lock
 * timeout, {@code 503 COUPON_BUSY}, optimistic-lock conflict on a shared product row) is retried here up to
 * {@link TransientRetry#ATTEMPTS} times with a short back-off ({@link TransientRetry}); every attempt
 * is a fresh transaction (the failed one was rolled back) and the settlement is idempotent. If the last attempt still
 * fails the event is logged and dropped; recovery then comes from the customer's verify poll
 * ({@code GET /payments/verify}, which asks Paystack and applies the same settlement) or, for an unpaid order, from
 * the expiry job, which cancels the order and gives its stock and coupon redemption back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookProcessorImpl implements WebhookProcessor {

    static final String EVENT_CHARGE_SUCCESS = "charge.success";
    static final String EVENT_CHARGE_FAILED = "charge.failed";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PaymentSettlementService settlementService;
    private final TransientRetry retry;

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
                    if (data.amount() == null || data.currency() == null || data.currency().isBlank()) {
                        // Fail closed: left for the customer's verify poll, which uses the provider's own data.
                        log.warn("Ignoring charge.success without amount/currency for reference {}", reference);
                        return;
                    }
                    var outcome = retry.callIncludingLockTimeouts("charge.success " + reference, () -> settlementService.applySuccess(reference,
                            data.channel(), data.amount(), data.currency(), body));
                    log.info("charge.success for reference {}: {}", reference, outcome);
                }
                case EVENT_CHARGE_FAILED -> {
                    var outcome = retry.callIncludingLockTimeouts("charge.failed " + reference, () -> settlementService.applyFailure(reference,
                            data.channel(), data.message(), body));
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
