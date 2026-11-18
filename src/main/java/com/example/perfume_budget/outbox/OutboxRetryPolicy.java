package com.example.perfume_budget.outbox;

import com.example.perfume_budget.notification.MailNotSentException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * EVT-01c (5): which failures of an outbox side effect are worth another attempt. A mail server outage, a timeout or
 * a database hiccup can pass; a programming error or bad data (a {@link NullPointerException} because the customer
 * has no default address, a {@link ClassCastException}, an illegal argument) fails the same way on every attempt, so
 * retrying it eight times over ~2 h only repeats the failure and delays the operator's ERROR. Those go FAILED at once.
 *
 * <p>Unknown exceptions stay retryable (a transport library's own exception types are many and mostly transient), and
 * so do {@link Error}s (an {@link OutOfMemoryError} can pass). {@link MailNotSentException} carries no cause (its cause
 * text can quote the recipient), so it records the verdict for its cause when it is created.
 */
public final class OutboxRetryPolicy {

    /** Deterministic failures: the same input fails the same way again. Subclasses included. */
    static final List<Class<? extends Throwable>> NOT_RETRYABLE = List.of(
            NullPointerException.class,
            ClassCastException.class,
            IllegalArgumentException.class,          // includes NumberFormatException
            IndexOutOfBoundsException.class,
            ArithmeticException.class,
            UnsupportedOperationException.class,
            ArrayStoreException.class,
            NegativeArraySizeException.class);

    private OutboxRetryPolicy() {
    }

    /** True if another attempt may succeed: no exception in {@code failure}'s cause chain is a deterministic one. */
    public static boolean retryable(Throwable failure) {
        Set<Throwable> seen = new HashSet<>();
        for (Throwable t = failure; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof MailNotSentException mail && !mail.retryable()) {
                return false;
            }
            for (Class<? extends Throwable> type : NOT_RETRYABLE) {
                if (type.isInstance(t)) {
                    return false;
                }
            }
        }
        return true;
    }
}
