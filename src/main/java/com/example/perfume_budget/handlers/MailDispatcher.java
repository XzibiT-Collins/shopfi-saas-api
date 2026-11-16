package com.example.perfume_budget.handlers;

import com.example.perfume_budget.config.AsyncConfig;
import com.example.perfume_budget.events.OrderStatusChangeEvent;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.Payment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * EVT-01: hands e-mail / receipt work that runs AFTER a commit to the dedicated, bounded mail executor
 * ({@link AsyncConfig#mailTaskExecutor()}), and makes sure that work is never lost silently: if the executor rejects
 * it (shut down) or the work itself throws (re-read, render or send failure), an ERROR names what was not sent and
 * its order / payment reference, so it can be re-sent by hand.
 *
 * <p>Only the reference is logged: never an e-mail address, an OTP / reset token or an exception message (a mail
 * transport error can quote the recipient). The exception class is enough to tell a transport outage from a bug.
 */
@Slf4j
@Component
public class MailDispatcher {

    private final Executor executor;

    public MailDispatcher(@Qualifier(AsyncConfig.MAIL_EXECUTOR) Executor executor) {
        this.executor = executor;
    }

    /**
     * Runs {@code work} on the mail executor. Never throws: it is called from after-commit callbacks, where an
     * exception would only be swallowed (and logged without the reference) by the transaction infrastructure.
     *
     * @param what      what the work sends, e.g. "customer receipt" (no personal data)
     * @param reference the order / payment reference, from {@link #reference(Object)}
     */
    public void submit(String what, String reference, Runnable work) {
        try {
            executor.execute(() -> {
                try {
                    work.run();
                } catch (RuntimeException | Error e) {
                    log.error("{} for {} was NOT sent: {}", what, reference, e.getClass().getName());
                }
            });
        } catch (RejectedExecutionException e) {
            log.error("{} for {} was NOT sent: the mail executor rejected it ({})", what, reference,
                    e.getClass().getSimpleName());
        }
    }

    /**
     * A log-safe reference for an event payload: the payment id and provider reference, the order number, or for
     * anything else just the payload type (OTP / password-reset events carry an e-mail address and a secret).
     */
    public static String reference(Object payload) {
        try {
            if (payload instanceof Payment p) {
                return "payment " + p.getId() + " (ref " + p.getProviderPaymentReference() + ")";
            }
            if (payload instanceof Order o) {
                return "order " + o.getOrderNumber();
            }
            if (payload instanceof OrderStatusChangeEvent e) {
                return "order " + e.orderNumber();
            }
            return payload == null ? "null" : payload.getClass().getSimpleName();
        } catch (RuntimeException unreadable) {
            return payload.getClass().getSimpleName();
        }
    }
}
