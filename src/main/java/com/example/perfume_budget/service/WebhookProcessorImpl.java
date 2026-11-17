package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.payment.request.PaystackWebhook;
import com.example.perfume_budget.dto.payment.request.WebhookData;
import com.example.perfume_budget.service.interfaces.WebhookProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Applies a signature-verified Paystack event, on the request thread under the tenant context the controller bound.
 *
 * <p>Event types are handled explicitly (PAY-01 AC5b):
 * <ul>
 *   <li>{@code charge.success} completes the payment (also a late success on a failed/cancelled-but-unpaid order);</li>
 *   <li>{@code charge.failed} is the only event that marks a payment FAILED;</li>
 *   <li>every other event type is ignored (never a terminal state change).</li>
 * </ul>
 * All state changes go through {@link PaymentSettlementService} (idempotent, order row lock).
 *
 * <p><b>Transient failures (API-04 rework 2).</b> A transient database failure while applying the event (deadlock
 * victim, serialization failure, lock timeout, {@code 503 COUPON_BUSY}, optimistic-lock conflict on a shared product
 * row) is retried here up to {@link TransientRetry#ATTEMPTS} times with a short back-off ({@link TransientRetry});
 * every attempt is a fresh transaction (the failed one was rolled back) and the settlement is idempotent. EVT-01c: each
 * attempt's lock waits are bounded ({@link WebhookLockTimeout}), so a held row cannot hold the request thread forever.
 *
 * <p><b>No acknowledgement without the commit (EVT-01c).</b> The event used to be applied on the webhook executor after
 * Paystack had been answered 200, so a settlement that did not commit (last retry failed, or a before-commit step such
 * as the side-effect outbox insert threw and rolled the payment change back) was acknowledged and never redelivered.
 * It now runs synchronously: if applying it fails, {@link WebhookNotAppliedException} propagates and the webhook
 * answers non-2xx, so Paystack redelivers it (the settlement is idempotent). A body that cannot be applied at all
 * (unparseable, no reference, an ignored event type, an unknown reference) is still acknowledged: a redelivery could
 * not change that.
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
    private final WebhookLockTimeout lockTimeout;

    @Override
    public void processWebhook(String body) {
        PaystackWebhook payload;
        try {
            payload = MAPPER.readValue(body, PaystackWebhook.class);
        } catch (Exception e) {
            // Never log the body or the parsed payload: it carries the customer's email and card details.
            log.error("Ignoring unreadable webhook body: {}", e.getClass().getSimpleName());
            return;
        }
        try {
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
                    var outcome = retry.callIncludingLockTimeouts("charge.success " + reference, () -> lockTimeout.call(() ->
                            settlementService.applySuccess(reference, data.channel(), data.amount(), data.currency(), body)));
                    log.info("charge.success for reference {}: {}", reference, outcome);
                }
                case EVENT_CHARGE_FAILED -> {
                    var outcome = retry.callIncludingLockTimeouts("charge.failed " + reference, () -> lockTimeout.call(() ->
                            settlementService.applyFailure(reference, data.channel(), data.message(), body)));
                    log.info("charge.failed for reference {}: {}", reference, outcome);
                }
                default -> log.info("Ignoring unhandled webhook event type for reference {}", reference);
            }
        } catch (RuntimeException e) {
            // not committed: answer non-2xx so the provider redelivers (EVT-01c). Class only, never the message.
            log.error("Webhook event not applied ({}); the provider is asked to redeliver it", e.getClass().getSimpleName());
            throw new WebhookNotAppliedException(e);
        }
    }

    /** The event was not applied (nothing committed); the webhook must not be acknowledged. Carries no message. */
    public static class WebhookNotAppliedException extends RuntimeException {
        WebhookNotAppliedException(Throwable cause) {
            super("Webhook event not applied: " + cause.getClass().getName(), null, false, false);
        }
    }
}
