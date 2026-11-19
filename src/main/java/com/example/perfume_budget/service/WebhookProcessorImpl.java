package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.payment.request.PaystackWebhook;
import com.example.perfume_budget.dto.payment.request.WebhookData;
import com.example.perfume_budget.service.WebhookSettlementBulkhead.WebhookBusyException;
import com.example.perfume_budget.service.interfaces.WebhookProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import com.example.perfume_budget.utils.SafeStackTrace;

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
 *
 * <p><b>Bulkhead and deadline (EVT-01c rework 1).</b> Only an event that needs a settlement takes one of the
 * {@link WebhookSettlementBulkhead} permits, before any connection; none free means {@link WebhookBusyException} (503,
 * redelivered). All attempts of one delivery share one budget ({@link WebhookLockTimeout#deadlineMillis()}): no attempt
 * starts with less than {@link WebhookLockTimeout#MIN_ATTEMPT_MILLIS} left, each attempt's lock_timeout is capped to
 * what is left, and running out is a failure like any other (500, redelivered).
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
    private final WebhookSettlementBulkhead bulkhead;

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
                    var outcome = settle("charge.success " + reference, () ->
                            settlementService.applySuccess(reference, data.channel(), data.amount(), data.currency(), body));
                    log.info("charge.success for reference {}: {}", reference, outcome);
                }
                case EVENT_CHARGE_FAILED -> {
                    var outcome = settle("charge.failed " + reference, () ->
                            settlementService.applyFailure(reference, data.channel(), data.message(), body));
                    log.info("charge.failed for reference {}: {}", reference, outcome);
                }
                default -> log.info("Ignoring unhandled webhook event type for reference {}", reference);
            }
        } catch (WebhookBusyException e) {
            if (e.reason() == WebhookBusyException.Reason.TENANT_SLOT) {
                log.warn("Webhook event not applied: this tenant's settlement slot is taken (per-tenant fairness limit); "
                        + "the provider is asked to redeliver it");
            } else {
                log.warn("Webhook event not applied: all settlement permits are taken; the provider is asked to redeliver it");
            }
            throw e;
        } catch (RuntimeException e) {
            // not committed: answer non-2xx so the provider redelivers (EVT-01c). Class only, never the message.
            log.error("Webhook event not applied; the provider is asked to redeliver it: {}", SafeStackTrace.of(e));
            throw new WebhookNotAppliedException(e);
        }
    }

    /**
     * Applies one settlement: a bulkhead permit first (no connection is held while waiting for it), then the retried
     * attempts, each in its own transaction with its lock waits capped to the delivery's remaining budget.
     */
    private <T> T settle(String what, Supplier<T> settlement) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(lockTimeout.deadlineMillis());
        // EVT-01d: the deadline is bound to this thread, so every later lock wait of the settlement (cart, products,
        // ledger, coupon) is limited to the budget left as well, not only the attempt's first one (SettlementDeadline)
        return SettlementDeadline.run(deadline, lockTimeout.timeoutMillis(), () ->
                bulkhead.call(() -> retry.callIncludingLockTimeouts(what, () -> {
                    long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (left < WebhookLockTimeout.MIN_ATTEMPT_MILLIS) {
                        throw new WebhookDeadlineExceededException();
                    }
                    return lockTimeout.call(Math.min(lockTimeout.timeoutMillis(), left), settlement);
                })));
    }

    /** The delivery's settlement budget ran out before an attempt committed; not retried, answered 500. */
    public static class WebhookDeadlineExceededException extends RuntimeException {
        // package-private: also thrown by SettlementDeadline at a lock point once the budget is gone
        WebhookDeadlineExceededException() {
            super("Webhook settlement deadline exceeded", null, false, false);
        }
    }

    /** The event was not applied (nothing committed); the webhook must not be acknowledged. Carries no message. */
    public static class WebhookNotAppliedException extends RuntimeException {
        WebhookNotAppliedException(Throwable cause) {
            super("Webhook event not applied: " + cause.getClass().getName(), null, false, false);
        }
    }
}
