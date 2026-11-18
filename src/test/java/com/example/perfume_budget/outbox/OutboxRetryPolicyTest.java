package com.example.perfume_budget.outbox;

import com.example.perfume_budget.notification.MailDeliveryException;
import com.example.perfume_budget.notification.MailNotSentException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/** EVT-01c (5): bugs and bad data go FAILED at once; outages and unknown failures are retried. */
class OutboxRetryPolicyTest {

    @Test
    void deterministicFailuresAreNotRetryable_alsoWhenWrapped() {
        assertThat(OutboxRetryPolicy.retryable(new NullPointerException())).isFalse();
        assertThat(OutboxRetryPolicy.retryable(new ClassCastException())).isFalse();
        assertThat(OutboxRetryPolicy.retryable(new IllegalArgumentException())).isFalse();
        assertThat(OutboxRetryPolicy.retryable(new NumberFormatException())).isFalse();
        assertThat(OutboxRetryPolicy.retryable(new ArrayIndexOutOfBoundsException())).isFalse();
        assertThat(OutboxRetryPolicy.retryable(new ArithmeticException())).isFalse();
        assertThat(OutboxRetryPolicy.retryable(new UnsupportedOperationException())).isFalse();
        assertThat(OutboxRetryPolicy.retryable(new RuntimeException("render", new NullPointerException()))).isFalse();
        assertThat(OutboxRetryPolicy.retryable(new MailNotSentException("Customer receipt", "payment 1", new NullPointerException())))
                .isFalse();
        assertThat(OutboxRetryPolicy.retryable(new IllegalStateException("x",
                new MailNotSentException("Customer receipt", "payment 1", new ClassCastException())))).isFalse();
    }

    @Test
    void outagesUnknownFailuresAndErrorsAreRetryable() {
        assertThat(OutboxRetryPolicy.retryable(new MailDeliveryException("550"))).isTrue();
        assertThat(OutboxRetryPolicy.retryable(new IllegalStateException("smtp down"))).isTrue();
        assertThat(OutboxRetryPolicy.retryable(new QueryTimeoutException("slow"))).isTrue();
        assertThat(OutboxRetryPolicy.retryable(new CannotAcquireLockException("deadlock"))).isTrue();
        assertThat(OutboxRetryPolicy.retryable(new MailNotSentException("Customer receipt", "payment 1",
                new MailDeliveryException("421 try later")))).isTrue();
        assertThat(OutboxRetryPolicy.retryable(new OutOfMemoryError())).isTrue();
        assertThat(OutboxRetryPolicy.retryable(new RuntimeException())).isTrue();
    }

    @Test
    void aCyclicCauseChainTerminates() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        assertThat(OutboxRetryPolicy.retryable(a)).isTrue();
    }
}
