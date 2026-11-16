package com.example.perfume_budget.notification;

/**
 * EVT-01b (L2): a receipt / admin e-mail could not be rendered or sent. Thrown (instead of only logged) so the
 * outbox retries it. Carries the reference and the cause's CLASS only, never the cause itself: a transport error's
 * message can quote the recipient's address.
 */
public class MailNotSentException extends RuntimeException {
    public MailNotSentException(String what, String reference, Throwable cause) {
        super(what + " for " + reference + " was not sent: " + cause.getClass().getName(), null, false, false);
    }
}
