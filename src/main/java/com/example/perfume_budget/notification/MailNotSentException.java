package com.example.perfume_budget.notification;

import com.example.perfume_budget.outbox.OutboxRetryPolicy;

/**
 * EVT-01b (L2): a receipt / admin e-mail could not be rendered or sent. Thrown (instead of only logged) so the
 * outbox retries it. Carries the reference and the cause's CLASS only, never the cause itself: a transport error's
 * message can quote the recipient's address. EVT-01c: since the cause is dropped, whether another attempt may succeed
 * ({@link OutboxRetryPolicy#retryable}) is decided here, from the cause, and kept.
 */
public class MailNotSentException extends RuntimeException {

    private final boolean retryable;

    public MailNotSentException(String what, String reference, Throwable cause) {
        super(what + " for " + reference + " was not sent: " + cause.getClass().getName(), null, false, false);
        this.retryable = OutboxRetryPolicy.retryable(cause);
    }

    /** False if the cause was a deterministic failure (a bug or bad data): the outbox marks the row FAILED at once. */
    public boolean retryable() {
        return retryable;
    }
}
