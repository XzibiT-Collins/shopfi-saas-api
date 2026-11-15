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
        String previous = (String) entityManager.createNativeQuery("SELECT current_setting('lock_timeout')")
                .getSingleResult();
        set(timeoutMillis + "ms");
        T result;
        try {
            result = action.get();
        } catch (RuntimeException e) {
            if (isLockTimeout(e)) {
                throw new ApiCodeException(HttpStatus.SERVICE_UNAVAILABLE, busyCode, busyMessage);
            }
            throw e;
        }
        set(previous);
        return result;
    }

    private void set(String value) {
        entityManager.createNativeQuery("SELECT set_config('lock_timeout', :v, true)")
                .setParameter("v", value)
                .getSingleResult();
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
