package com.example.perfume_budget.outbox;

import com.example.perfume_budget.handlers.SuccessfulOrderHandler;
import com.example.perfume_budget.model.Payment;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** EVT-01c (8): pins the outbox numbers and the listener order that the EVT-01b review mutants changed unnoticed. */
class OutboxStoreConstantsTest {

    /** R22: eight attempts, as documented (the integration tests only used MAX_ATTEMPTS relative to itself). */
    @Test
    void maxAttemptsIsEight() {
        assertThat(OutboxStore.MAX_ATTEMPTS).isEqualTo(8);
    }

    /** R05: exponential from 1 min, capped at 1 h (attempt 7 would be 64 min). */
    @Test
    void backoffDoublesFromOneMinuteAndIsCappedAtOneHour() {
        assertThat(OutboxStore.backoff(0)).isEqualTo(Duration.ofMinutes(1));
        assertThat(OutboxStore.backoff(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(OutboxStore.backoff(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(OutboxStore.backoff(6)).isEqualTo(Duration.ofMinutes(32));
        assertThat(OutboxStore.backoff(7)).isEqualTo(Duration.ofHours(1));
        assertThat(OutboxStore.backoff(8)).isEqualTo(Duration.ofHours(1));
        assertThat(OutboxStore.backoff(1_000)).isEqualTo(Duration.ofHours(1));
        assertThat(OutboxStore.MAX_BACKOFF).isEqualTo(Duration.ofHours(1));
        assertThat(OutboxStore.LEASE).isEqualTo(Duration.ofMinutes(10));
    }

    /**
     * R17: the outbox rows are recorded FIRST among the before-commit hooks (an INSERT that locks no existing row and
     * flushes nothing, so the API-05 / coupon lock order of the commit is unchanged).
     */
    @Test
    void theOutboxRecordersRunFirstAmongTheBeforeCommitHooks() throws Exception {
        for (Method m : new Method[]{SuccessfulOrderHandler.class.getMethod("recordPaymentSideEffects", Payment.class),
                SuccessfulOrderHandler.class.getMethod("recordNewOrderMail", com.example.perfume_budget.model.Order.class)}) {
            org.springframework.core.annotation.Order order = m.getAnnotation(org.springframework.core.annotation.Order.class);
            assertThat(order).as(m.getName()).isNotNull();
            assertThat(order.value()).as(m.getName()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        }
    }

    /** EVT-01c (7): the drain is not a {@code @Scheduled} job on Spring's shared scheduling thread. */
    @Test
    void theDrainerIsNotOnTheSharedSchedulerThread() {
        for (Method m : OutboxDrainer.class.getDeclaredMethods()) {
            assertThat(m.getAnnotation(Scheduled.class)).as(m.getName()).isNull();
        }
    }
}
