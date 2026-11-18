package com.example.perfume_budget.service;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/**
 * EVT-01c: the webhook settles on the request thread (it answers only after the commit), so no lock wait in the
 * settlement may be unbounded: a product / order / coupon row held by another transaction would otherwise tie up the
 * request thread (and Paystack's delivery) indefinitely. Runs one settlement attempt in a transaction whose
 * {@code lock_timeout} is at most {@code shopfi.webhook.lock-timeout-ms} (SET LOCAL: this transaction only); a wait that
 * hits it (55P03) is a transient failure, retried by {@link TransientRetry}, and after the last attempt the webhook
 * answers non-2xx so Paystack redelivers. Inner {@link DbLockTimeout} sections still apply their own limit and restore
 * this one.
 *
 * <p>EVT-01c rework 1: the limit is per lock wait, so k waits x 3 attempts could outlast Paystack's 30 s delivery
 * timeout. {@code shopfi.webhook.settlement-deadline-ms} (20 s) is the budget for all attempts of one delivery: the
 * processor starts no attempt with less than {@link #MIN_ATTEMPT_MILLIS} left, and caps each attempt's lock_timeout to
 * what is left.
 *
 * <p>EVT-01d: the deadline is also bound to the settling thread ({@link SettlementDeadline}), so every later lock wait of
 * the attempt is limited to the budget left too, and the budget is validated at startup ({@link #validate()}).
 */
@Component
public class WebhookLockTimeout {

    /** An attempt is not started with less budget than this left (it could not wait for a single lock). */
    public static final long MIN_ATTEMPT_MILLIS = 250;

    /** Paystack gives up on a delivery after 30 s; the whole settlement budget must end before that. */
    public static final long PROVIDER_TIMEOUT_MILLIS = 30_000;

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${shopfi.webhook.lock-timeout-ms:8000}")
    private long timeoutMillis;

    @Value("${shopfi.webhook.settlement-deadline-ms:20000}")
    private long deadlineMillis;

    /**
     * EVT-01d: fail fast at startup on a budget that cannot work. At or below {@link #MIN_ATTEMPT_MILLIS} no attempt
     * ever starts (every settlement webhook would answer 500 forever); at or above Paystack's 30 s the provider gives up
     * on a delivery we may still commit. A per-wait lock limit below 1 ms would mean "no limit" to Postgres.
     */
    @PostConstruct
    void validate() {
        validate(timeoutMillis, deadlineMillis);
    }

    static void validate(long lockTimeoutMillis, long settlementDeadlineMillis) {
        if (settlementDeadlineMillis <= MIN_ATTEMPT_MILLIS || settlementDeadlineMillis >= PROVIDER_TIMEOUT_MILLIS) {
            throw new IllegalStateException("shopfi.webhook.settlement-deadline-ms must be more than " + MIN_ATTEMPT_MILLIS
                    + " ms (no settlement attempt starts with less left) and less than " + PROVIDER_TIMEOUT_MILLIS
                    + " ms (Paystack's webhook delivery timeout), but is " + settlementDeadlineMillis + " ms");
        }
        if (lockTimeoutMillis < 1) {
            throw new IllegalStateException("shopfi.webhook.lock-timeout-ms must be at least 1 ms, but is "
                    + lockTimeoutMillis + " ms");
        }
    }

    /** The per-lock-wait limit of one attempt. */
    public long timeoutMillis() {
        return timeoutMillis;
    }

    /** The budget for all settlement attempts of one webhook delivery. */
    public long deadlineMillis() {
        return deadlineMillis;
    }

    /** One settlement attempt in its own transaction, every lock wait in it bounded by {@code lockTimeoutMillis}. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T call(long lockTimeoutMillis, Supplier<T> settlement) {
        entityManager.createNativeQuery("SELECT set_config('lock_timeout', :v, true)")
                .setParameter("v", Math.max(1, lockTimeoutMillis) + "ms")
                .getSingleResult();
        return settlement.get();
    }
}
