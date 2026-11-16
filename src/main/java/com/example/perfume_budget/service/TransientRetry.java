package com.example.perfume_budget.service;

import com.example.perfume_budget.exception.ApiCodeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Bounded in-process retry of a WHOLE transaction (API-04 rework 2) for the paths that give a coupon redemption back
 * or take it again: owner / admin cancel, the expiry job, the verify poll and the payment webhooks. Each attempt must
 * open and commit its own transaction (so this refuses to run inside one), and the retried operations are idempotent
 * (they lock the order row and decide from the state read after the lock).
 *
 * <p>Retried: a concurrency conflict the database or Hibernate reports when two of these paths touch the same rows
 * at once: deadlock victim (40P01), serialization failure (40001), optimistic-lock conflict on a shared row (e.g. the
 * product both a cancel and a failure webhook re-stock). Not retried on HTTP paths: a lock wait that already ran into
 * lock_timeout (55P03, {@code 503 COUPON_BUSY}); the webhook ({@link #callIncludingLockTimeouts}) retries those too.
 */
@Slf4j
@Component
public class TransientRetry {

    public static final int ATTEMPTS = 3;

    private final long backoffMillis;

    public TransientRetry(@Value("${shopfi.retry.backoff-ms:100}") long backoffMillis) {
        this.backoffMillis = Math.max(0, backoffMillis);
    }

    /**
     * HTTP / job paths. A conflict that survives the last attempt becomes {@code 503 {data.code:"TRY_AGAIN"}} (the
     * transaction was rolled back, nothing changed): never a 500.
     */
    public <T> T call(String what, Supplier<T> action) {
        try {
            return run(what, action, DbLockTimeout::isConcurrencyConflict);
        } catch (RuntimeException e) {
            if (DbLockTimeout.isConcurrencyConflict(e)) {
                throw new ApiCodeException(HttpStatus.SERVICE_UNAVAILABLE, DbLockTimeout.TRY_AGAIN,
                        DbLockTimeout.TRY_AGAIN_MESSAGE);
            }
            throw e;
        }
    }

    /** Webhook: also retries lock timeouts / COUPON_BUSY; the last failure is rethrown unchanged. */
    public <T> T callIncludingLockTimeouts(String what, Supplier<T> action) {
        return run(what, action, e -> DbLockTimeout.isConcurrencyConflict(e) || DbLockTimeout.isTransient(e));
    }

    private <T> T run(String what, Supplier<T> action, Predicate<Throwable> retryable) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A retried transaction must not run inside another transaction");
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                if (attempt >= ATTEMPTS || !retryable.test(e)) {
                    if (retryable.test(e)) {
                        log.warn("{}: gave up after {} attempts ({})", what, attempt, e.getClass().getSimpleName());
                    }
                    throw e;
                }
                log.info("{}: concurrent update ({}), attempt {} of {}, retrying", what, e.getClass().getSimpleName(),
                        attempt, ATTEMPTS);
                sleep(backoffMillis * attempt);
            }
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry", e);
        }
    }
}
