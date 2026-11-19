package com.example.perfume_budget.service;

import com.example.perfume_budget.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
 *
 * <p><b>Per-tenant fairness (EVT-01d).</b> The permits are shared by all tenants, and a tenant knows its own Paystack
 * secret, so it could flood signed webhooks (for orders stuck behind a held row) and keep every permit busy, delaying
 * every other tenant's settlements. So one tenant holds at most
 * {@code shopfi.payments.webhook.max-concurrent-settlements-per-tenant} (default 1) of them at a time: a webhook first
 * takes its tenant's slot, then a shared permit, both within the same short wait; a tenant whose slot is taken gets the
 * same 503 without ever competing for a shared permit. With the defaults a flooding tenant holds at most 1 of the 3
 * permits and the other tenants keep 2. The tenant is the one the webhook token selected ({@link TenantContext}).
 * Order: tenant slot -> shared permit -> connection -> row locks; both are released in reverse order.
 */
@Component
public class WebhookSettlementBulkhead {

    static final long MAX_WAIT_MILLIS = 100;

    /** Not final: an integration test swaps in a smaller one (the test profile raises the limit for its race tests). */
    private volatile Semaphore permits;
    private final long waitMillis;
    /** Not final: the fairness integration test sets the production value (the test profile raises it). */
    private volatile int perTenant;
    /** One slot semaphore per tenant schema, created on its first settlement (bounded by the number of tenants). */
    private final ConcurrentMap<String, Semaphore> tenantSlots = new ConcurrentHashMap<>();

    @Autowired
    public WebhookSettlementBulkhead(@Value("${shopfi.payments.webhook.max-concurrent-settlements:3}") int maxConcurrent,
                                     @Value("${shopfi.payments.webhook.max-concurrent-settlements-per-tenant:1}") int maxPerTenant,
                                     @Value("${shopfi.payments.webhook.permit-wait-ms:50}") long waitMillis) {
        this.permits = new Semaphore(Math.max(1, maxConcurrent));
        this.perTenant = Math.max(1, maxPerTenant);
        this.waitMillis = Math.min(MAX_WAIT_MILLIS, Math.max(0, waitMillis));
    }

    /** No per-tenant limit beyond the shared one (unit tests of the shared permits). */
    public WebhookSettlementBulkhead(int maxConcurrent, long waitMillis) {
        this(maxConcurrent, maxConcurrent, waitMillis);
    }

    /**
     * Runs {@code settlement} holding the current tenant's slot and a shared permit; {@link WebhookBusyException} if
     * either does not free up within the short wait.
     */
    public <T> T call(Supplier<T> settlement) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // a transaction here would hold a connection while waiting for the permit, which is what this prevents
            throw new IllegalStateException("The webhook bulkhead must be entered outside a transaction");
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        Semaphore slot = tenantSlots.computeIfAbsent(TenantContext.getSchemaOrDefault(), k -> new Semaphore(perTenant));
        if (!tryAcquire(slot, deadline)) {
            throw new WebhookBusyException(WebhookBusyException.Reason.TENANT_SLOT);
        }
        try {
            Semaphore s = permits;
            if (!tryAcquire(s, deadline)) {
                throw new WebhookBusyException(WebhookBusyException.Reason.SHARED_PERMITS);
            }
            try {
                return settlement.get();
            } finally {
                s.release();
            }
        } finally {
            slot.release();
        }
    }

    private static boolean tryAcquire(Semaphore s, long deadlineNanos) {
        try {
            return s.tryAcquire(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public int availablePermits() {
        return permits.availablePermits();
    }

    /** Free slots of one tenant (its schema); the per-tenant limit when it has not settled anything yet. */
    public int availableTenantSlots(String tenantSchema) {
        Semaphore slot = tenantSlots.get(tenantSchema);
        return slot == null ? perTenant : slot.availablePermits();
    }

    /** All settlement permits are taken; the webhook answers 503 so the provider redelivers. Carries no detail. */
    public static class WebhookBusyException extends RuntimeException {
        /** Why the settlement was refused (EVT-01d low): ops can tell a fairness refusal from global saturation. */
        public enum Reason { SHARED_PERMITS, TENANT_SLOT }

        private final Reason reason;

        public WebhookBusyException() {
            this(Reason.SHARED_PERMITS);
        }

        public WebhookBusyException(Reason reason) {
            super("Webhook settlement capacity busy", null, false, false);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }
}
