package com.example.perfume_budget.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * EVT-01c rework 1: bulkhead around the synchronous webhook settlement. Every signed webhook used to settle on a Tomcat
 * thread with no limit, each holding a pooled connection while it waited for row locks (product, order, coupon,
 * ledger), so a burst of webhooks for one hot product could take the whole Hikari pool and stall unrelated requests.
 *
 * <p>At most {@code shopfi.payments.webhook.max-concurrent-settlements} (default 3) settlements run at once. The permit
 * is taken BEFORE any connection: the caller must not be in a transaction, and the wait for a free permit is short
 * ({@code shopfi.payments.webhook.permit-wait-ms}, at most {@value #MAX_WAIT_MILLIS} ms). No free permit means
 * {@link WebhookBusyException}: the webhook answers 503 and Paystack redelivers later (the settlement is idempotent).
 * The permit is released however the settlement ends.
 */
@Component
public class WebhookSettlementBulkhead {

    static final long MAX_WAIT_MILLIS = 100;

    /** Not final: an integration test swaps in a smaller one (the test profile raises the limit for its race tests). */
    private volatile Semaphore permits;
    private final long waitMillis;

    public WebhookSettlementBulkhead(@Value("${shopfi.payments.webhook.max-concurrent-settlements:3}") int maxConcurrent,
                                     @Value("${shopfi.payments.webhook.permit-wait-ms:50}") long waitMillis) {
        this.permits = new Semaphore(Math.max(1, maxConcurrent));
        this.waitMillis = Math.min(MAX_WAIT_MILLIS, Math.max(0, waitMillis));
    }

    /** Runs {@code settlement} holding a permit; {@link WebhookBusyException} if none frees up within the short wait. */
    public <T> T call(Supplier<T> settlement) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // a transaction here would hold a connection while waiting for the permit, which is what this prevents
            throw new IllegalStateException("The webhook bulkhead must be entered outside a transaction");
        }
        Semaphore s = permits;
        boolean acquired;
        try {
            acquired = s.tryAcquire(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            throw new WebhookBusyException();
        }
        try {
            return settlement.get();
        } finally {
            s.release();
        }
    }

    public int availablePermits() {
        return permits.availablePermits();
    }

    /** All settlement permits are taken; the webhook answers 503 so the provider redelivers. Carries no detail. */
    public static class WebhookBusyException extends RuntimeException {
        public WebhookBusyException() {
            super("Webhook settlement capacity busy", null, false, false);
        }
    }
}
