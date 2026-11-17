package com.example.perfume_budget.service;

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
 * {@code lock_timeout} is {@code shopfi.webhook.lock-timeout-ms} (SET LOCAL: this transaction only); a wait that hits
 * it (55P03) is a transient failure, retried by {@link TransientRetry}, and after the last attempt the webhook answers
 * non-2xx so Paystack redelivers. Inner {@link DbLockTimeout} sections still apply their own limit and restore this one.
 */
@Component
public class WebhookLockTimeout {

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${shopfi.webhook.lock-timeout-ms:8000}")
    private long timeoutMillis;

    public long timeoutMillis() {
        return timeoutMillis;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T call(Supplier<T> settlement) {
        entityManager.createNativeQuery("SELECT set_config('lock_timeout', :v, true)")
                .setParameter("v", Math.max(1, timeoutMillis) + "ms")
                .getSingleResult();
        return settlement.get();
    }
}
