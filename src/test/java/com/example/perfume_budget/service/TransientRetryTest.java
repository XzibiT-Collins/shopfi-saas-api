package com.example.perfume_budget.service;

import com.example.perfume_budget.exception.ApiCodeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** API-04 rework 2: bounded whole-transaction retry for the cancel / expiry / verify / webhook paths. */
class TransientRetryTest {

    private final TransientRetry retry = new TransientRetry(0);

    @AfterEach
    void cleanup() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private static RuntimeException deadlock() {
        return new CannotAcquireLockException("d", new SQLException("deadlock detected", "40P01"));
    }

    private static RuntimeException lockTimeout() {
        return new CannotAcquireLockException("t", new SQLException("lock timeout", "55P03"));
    }

    @Test
    void aConflictIsRetriedUntilItSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        String r = retry.call("t", () -> {
            int n = calls.incrementAndGet();
            if (n == 1) throw deadlock();
            if (n == 2) throw new ObjectOptimisticLockingFailureException("Product", 1L);
            return "ok";
        });
        assertThat(r).isEqualTo("ok");
        assertThat(calls).hasValue(3);
    }

    @Test
    void aConflictThatOutlastsTheAttemptsIsA503TryAgain_neverA500() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> retry.call("t", () -> {
            calls.incrementAndGet();
            throw new RuntimeException(new SQLException("could not serialize", "40001"));
        })).isInstanceOfSatisfying(ApiCodeException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.getCode()).isEqualTo(DbLockTimeout.TRY_AGAIN);
        });
        assertThat(calls).hasValue(TransientRetry.ATTEMPTS);
    }

    @Test
    void lockTimeoutsAndOtherErrorsAreNotRetriedOnHttpPaths() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> retry.call("t", () -> {
            calls.incrementAndGet();
            throw lockTimeout();
        })).isInstanceOf(CannotAcquireLockException.class);
        ApiCodeException busy = new ApiCodeException(HttpStatus.SERVICE_UNAVAILABLE, CouponRedemptionService.BUSY, "b");
        assertThatThrownBy(() -> retry.call("t", () -> {
            calls.incrementAndGet();
            throw busy;
        })).isSameAs(busy);
        assertThatThrownBy(() -> retry.call("t", () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("bug");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(3);
    }

    @Test
    void theWebhookVariantAlsoRetriesLockTimeouts_andRethrowsTheLastFailure() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeException last = lockTimeout();
        assertThatThrownBy(() -> retry.callIncludingLockTimeouts("t", () -> {
            calls.incrementAndGet();
            throw last;
        })).isSameAs(last);
        assertThat(calls).hasValue(TransientRetry.ATTEMPTS);
    }

    @Test
    void refusesToRunInsideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> retry.call("t", () -> "x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theBackOffGrowsWithTheAttempt() {
        TransientRetry slow = new TransientRetry(40);
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> slow.call("t", () -> {
            throw deadlock();
        })).isInstanceOf(ApiCodeException.class);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(ms).as("40 ms after attempt 1, 80 ms after attempt 2").isGreaterThanOrEqualTo(120);
    }
}
