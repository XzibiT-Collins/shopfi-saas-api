package com.example.perfume_budget.service;

import jakarta.persistence.EntityManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * EVT-01d: the webhook settlement's overall deadline, bound to the settling thread, from which EVERY lock wait limit of
 * the settlement is derived. Before, only the attempt's first {@code lock_timeout} was capped to the budget left: each
 * later lock wait in the same attempt (cart, products, ledger accounts) could again take the full per-wait limit (8 s),
 * and the coupon sections ({@link DbLockTimeout}) always set their fixed 5 s, so one delivery could outlast its 20 s
 * budget (and Paystack's 30 s delivery timeout).
 *
 * <p>Now every lock point of the settlement path calls {@link #beforeLockWait(EntityManager)} right before its
 * lock-taking statement: it sets {@code lock_timeout} to {@code min(per-wait cap, time left)} and
 * {@code statement_timeout} to the time left (one statement can wait for several rows, e.g. the product locks), both
 * {@code SET LOCAL} (this transaction only). The coupon sections narrow the cap to their own 5 s ({@link #limit}) and put
 * back the derived value afterwards. Once nothing is left the settlement fails with
 * {@link WebhookProcessorImpl.WebhookDeadlineExceededException} (rolled back, not retried, answered 500, redelivered).
 *
 * <p>Outside a webhook settlement (checkout, cancel, verify poll, jobs) no deadline is bound and every method here is a
 * no-op that runs no SQL. Postgres {@code transaction_timeout} was considered: it exists only from PostgreSQL 17 (the
 * compose file runs 16) and it TERMINATES the session (a pooled connection dies, FATAL instead of a classifiable
 * error), so it is not used.
 */
public final class SettlementDeadline {

    private static final ThreadLocal<Budget> CURRENT = new ThreadLocal<>();

    private SettlementDeadline() {
    }

    private static final class Budget {
        final long deadlineNanos;
        long capMillis;

        Budget(long deadlineNanos, long capMillis) {
            this.deadlineNanos = deadlineNanos;
            this.capMillis = capMillis;
        }
    }

    /**
     * Runs {@code body} with the settlement deadline {@code deadlineNanos} ({@link System#nanoTime()} based) and the
     * per-lock-wait cap {@code capMillis} bound to this thread; the previous binding (normally none) is restored after.
     */
    public static <T> T run(long deadlineNanos, long capMillis, Supplier<T> body) {
        Budget previous = CURRENT.get();
        CURRENT.set(new Budget(deadlineNanos, Math.max(1, capMillis)));
        try {
            return body.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** Whether a settlement deadline is bound to this thread. */
    public static boolean active() {
        return CURRENT.get() != null;
    }

    /** Milliseconds left until the bound deadline (negative once passed); {@link Long#MAX_VALUE} when none is bound. */
    public static long remainingMillis() {
        Budget b = CURRENT.get();
        return b == null ? Long.MAX_VALUE : TimeUnit.NANOSECONDS.toMillis(b.deadlineNanos - System.nanoTime());
    }

    /**
     * The limit for one lock wait that would otherwise be {@code capMillis}: {@code capMillis} itself when no deadline is
     * bound, else {@code min(capMillis, bound cap, time left)}. Never 0 (which would mean "no limit" to Postgres).
     *
     * @throws WebhookProcessorImpl.WebhookDeadlineExceededException when the bound deadline has passed
     */
    public static long limit(long capMillis) {
        Budget b = CURRENT.get();
        if (b == null) {
            return Math.max(1, capMillis);
        }
        long left = remainingMillis();
        if (left <= 0) {
            throw new WebhookProcessorImpl.WebhookDeadlineExceededException();
        }
        return Math.max(1, Math.min(Math.min(capMillis, b.capMillis), left));
    }

    /**
     * Narrows the per-wait cap to {@code capMillis} while {@code body} runs (a coupon section: 5 s, never more than the
     * time left). Without a bound deadline it just runs {@code body}.
     */
    static <T> T withCap(long capMillis, Supplier<T> body) {
        Budget b = CURRENT.get();
        if (b == null) {
            return body.get();
        }
        long outer = b.capMillis;
        b.capMillis = Math.max(1, Math.min(outer, capMillis));
        try {
            return body.get();
        } finally {
            b.capMillis = outer;
        }
    }

    /**
     * Call right before a lock-taking statement of the settlement path. With a bound deadline and a transaction: sets
     * {@code lock_timeout} to {@link #limit} of the bound cap and {@code statement_timeout} to the time left, both for
     * this transaction only. Otherwise does nothing (no SQL).
     *
     * @throws WebhookProcessorImpl.WebhookDeadlineExceededException when the bound deadline has passed
     */
    public static void beforeLockWait(EntityManager entityManager) {
        Budget b = CURRENT.get();
        if (b == null || !TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        long lockMillis = limit(b.capMillis);
        long statementMillis = Math.max(1, remainingMillis());
        entityManager.createNativeQuery("SELECT set_config('lock_timeout', :l, true), set_config('statement_timeout', :s, true)")
                .setParameter("l", lockMillis + "ms")
                .setParameter("s", statementMillis + "ms")
                .getSingleResult();
    }
}
