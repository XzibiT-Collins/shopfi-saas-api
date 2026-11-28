package com.example.perfume_budget.platform.billing.paystack;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTest {

    /** A clock the test moves by hand. */
    static final class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-28T10:00:00Z"));

        void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    @Test
    void opensAfterTheThresholdOfConsecutiveFailuresAndRefusesCalls() {
        MutableClock clock = new MutableClock();
        CircuitBreaker b = new CircuitBreaker(3, Duration.ofSeconds(30), clock);
        for (int i = 0; i < 2; i++) {
            assertThat(b.tryAcquire()).isTrue();
            b.failure();
        }
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(b.tryAcquire()).isTrue();
        b.failure();
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(b.tryAcquire()).isFalse();
        clock.advance(Duration.ofSeconds(29));
        assertThat(b.tryAcquire()).isFalse();
    }

    @Test
    void aSuccessResetsTheFailureCount() {
        CircuitBreaker b = new CircuitBreaker(3, Duration.ofSeconds(30), new MutableClock());
        b.failure();
        b.failure();
        b.success();
        b.failure();
        b.failure();
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void afterTheOpenPeriodExactlyOneProbeIsLetThroughAndSuccessCloses() {
        MutableClock clock = new MutableClock();
        CircuitBreaker b = new CircuitBreaker(1, Duration.ofSeconds(30), clock);
        b.failure();
        clock.advance(Duration.ofSeconds(30));
        assertThat(b.tryAcquire()).isTrue();          // the probe
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(b.tryAcquire()).isFalse();         // everybody else still refused while the probe runs
        b.success();
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(b.tryAcquire()).isTrue();
    }

    @Test
    void aFailedProbeReopensForAFullPeriod() {
        MutableClock clock = new MutableClock();
        CircuitBreaker b = new CircuitBreaker(1, Duration.ofSeconds(30), clock);
        b.failure();
        clock.advance(Duration.ofSeconds(31));
        assertThat(b.tryAcquire()).isTrue();
        b.failure();
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.OPEN);
        clock.advance(Duration.ofSeconds(29));
        assertThat(b.tryAcquire()).isFalse();
        clock.advance(Duration.ofSeconds(2));
        assertThat(b.tryAcquire()).isTrue();
    }
}
