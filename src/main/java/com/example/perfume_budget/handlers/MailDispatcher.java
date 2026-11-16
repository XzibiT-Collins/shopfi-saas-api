package com.example.perfume_budget.handlers;

import com.example.perfume_budget.config.AsyncConfig;
import com.example.perfume_budget.events.OrderStatusChangeEvent;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.Payment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final Executor notificationExecutor;

    @Autowired
    public MailDispatcher(@Qualifier(AsyncConfig.MAIL_EXECUTOR) Executor executor,
                          @Qualifier(AsyncConfig.NOTIFICATION_EXECUTOR) Executor notificationExecutor) {
        this.executor = executor;
        this.notificationExecutor = notificationExecutor;
    }

    /** One executor for both kinds of work (unit tests). */
    public MailDispatcher(Executor executor) {
        this(executor, executor);
    }

    /**
     * Runs {@code work} on the mail executor. Never throws: it is called from after-commit callbacks, where an
     * exception would only be swallowed (and logged without the reference) by the transaction infrastructure.
     *
     * @param what      what the work sends, e.g. "customer receipt" (no personal data)
     * @param reference the order / payment reference, from {@link #reference(Object)}
     */
    public void submit(String what, String reference, Runnable work) {
        submitTo(executor, what, reference, work);
    }

    /**
     * Rework 1: runs after-commit in-app notification work (notification rows + STOMP push, no mail server involved)
     * on its own executor, so a hung mail server neither delays nor drops it. Same never-throw / log-the-reference
     * contract as {@link #submit}.
     */
    public void submitNotification(String what, String reference, Runnable work) {
        submitTo(notificationExecutor, what, reference, work);
    }

    /**
     * Never runs {@code work} on the calling thread: the caller is inside the after-commit callback of its own
     * transaction, whose (committed) transaction and JDBC connection are still bound to the thread. A saturated
     * executor makes the caller wait briefly, then the work is dropped and logged ({@link AsyncConfig#MAIL_SUBMIT_WAIT_MILLIS}).
     */
    private static void submitTo(Executor target, String what, String reference, Runnable work) {
        try {
            target.execute(() -> {
                try {
                    work.run();
                } catch (RuntimeException | Error e) {
                    log.error("{} for {} was NOT sent: {}", what, reference, e.getClass().getName());
                }
            });
        } catch (RejectedExecutionException e) {
            // Spring wraps the policy's own rejection (e.g. "queue full after waiting 1000 ms"): log that one
            Throwable reason = e.getCause() instanceof RejectedExecutionException cause ? cause : e;
            log.error("{} for {} was NOT sent: the executor rejected it ({})", what, reference, reason.getMessage());
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
