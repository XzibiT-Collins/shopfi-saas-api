package com.example.perfume_budget.service;

import com.example.perfume_budget.exception.ApiCodeException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.function.Supplier;

/**
 * Bounded row-lock waits (API-04 rework). Runs an action with Postgres {@code lock_timeout} set for the current
 * transaction only ({@code set_config(..., true)} = {@code SET LOCAL}) and puts the previous value back afterwards, so
 * the rest of the caller's transaction keeps its own setting. A lock wait that hits the timeout (SQLSTATE 55P03)
 * becomes a clean {@code 503} with a stable {@code data.code} instead of a 500 or an unbounded wait.
 *
 * <p>EVT-01d: inside a webhook settlement ({@link SettlementDeadline} bound) the limit is never more than the
 * settlement's budget left, instead of a fixed 5 s.
 */
@Component
public class DbLockTimeout {

    public static final String LOCK_NOT_AVAILABLE = "55P03";

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${shopfi.coupon.lock-timeout-ms:5000}")
    private long timeoutMillis;

    public long timeoutMillis() {
        return timeoutMillis;
    }

    /** Must run inside the caller's transaction (SET LOCAL needs one). */
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T run(String busyCode, String busyMessage, Supplier<T> action) {
        if (SettlementDeadline.active()) {
            // EVT-01d: inside a webhook settlement the wait is min(5 s, budget left), and afterwards the settlement's
            // own derived limit (min(per-wait cap, budget left) at that moment) is put back, not a stale value
            set(SettlementDeadline.limit(timeoutMillis) + "ms");
            T result = SettlementDeadline.withCap(timeoutMillis, () -> guarded(busyCode, busyMessage, action));
            SettlementDeadline.beforeLockWait(entityManager);
            return result;
        }
        String previous = (String) entityManager.createNativeQuery("SELECT current_setting('lock_timeout')")
                .getSingleResult();
        set(timeoutMillis + "ms");
        T result = guarded(busyCode, busyMessage, action);
        set(previous);
        return result;
    }

    private static <T> T guarded(String busyCode, String busyMessage, Supplier<T> action) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            if (isLockTimeout(e)) {
                throw new ApiCodeException(HttpStatus.SERVICE_UNAVAILABLE, busyCode, busyMessage);
            }
            throw e;
        }
    }

    private void set(String value) {
        entityManager.createNativeQuery("SELECT set_config('lock_timeout', :v, true)")
                .setParameter("v", value)
                .getSingleResult();
    }

    public static final String DEADLOCK_DETECTED = "40P01";
    public static final String SERIALIZATION_FAILURE = "40001";

    /** Stable {@code data.code} of the 503 for a deadlock / serialization failure / lock timeout outside a coupon. */
    public static final String TRY_AGAIN = "TRY_AGAIN";
    public static final String TRY_AGAIN_MESSAGE = "The request collided with another update. Please try again.";

    /**
     * A failure that is safe and useful to retry: Postgres picked this transaction as a deadlock victim (40P01), a
     * serialization failure (40001), a lock wait that hit lock_timeout (55P03) however Spring / Hibernate / JPA wrapped
     * it, or a {@code 503 COUPON_BUSY}. The whole transaction was rolled back, so a retry starts from scratch.
     * Optimistic-lock failures are NOT included (API-05 owns those).
     */
    public static boolean isTransient(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ApiCodeException api && api.getStatus() == HttpStatus.SERVICE_UNAVAILABLE) {
                return true;
            }
            if (t instanceof org.springframework.dao.PessimisticLockingFailureException
                    || t instanceof jakarta.persistence.PessimisticLockException
                    || t instanceof jakarta.persistence.LockTimeoutException
                    || t instanceof org.hibernate.exception.LockAcquisitionException) {
                return true;
            }
            if (t instanceof SQLException sql && (DEADLOCK_DETECTED.equals(sql.getSQLState())
                    || SERIALIZATION_FAILURE.equals(sql.getSQLState()) || LOCK_NOT_AVAILABLE.equals(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Two transactions touched the same rows at once and this one lost: deadlock victim (40P01), serialization
     * failure (40001) or an optimistic-lock conflict. Rolled back, nothing changed, an immediate retry is expected to
     * succeed. A lock wait that hit lock_timeout is NOT one (it already waited the full limit).
     */
    public static boolean isConcurrencyConflict(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof org.springframework.dao.OptimisticLockingFailureException
                    || t instanceof jakarta.persistence.OptimisticLockException
                    || t instanceof org.hibernate.StaleStateException
                    || t instanceof org.springframework.dao.DeadlockLoserDataAccessException) {
                return true;
            }
            if (t instanceof SQLException sql && (DEADLOCK_DETECTED.equals(sql.getSQLState())
                    || SERIALIZATION_FAILURE.equals(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }

    /** A lock wait that ran into lock_timeout (SQLSTATE 55P03), however Spring / Hibernate / JPA wrapped it. */
    public static boolean isLockTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
                return true;
            }
            if (t instanceof jakarta.persistence.LockTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
