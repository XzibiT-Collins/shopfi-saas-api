package com.example.perfume_budget.platform.billing.paystack;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * A small circuit breaker, one per Paystack environment. CLOSED counts consecutive failures; at {@code failureThreshold}
 * it OPENS and refuses every call for {@code openFor}; then ONE probe call is let through (HALF_OPEN): success closes it,
 * a failure opens it again for another {@code openFor}. Only provider trouble counts as a failure (5xx, 429, timeouts,
 * connect failures); a 4xx is an answer and counts as a success. Thread-safe; time comes from the injected clock.
 */
public class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final Duration openFor;
    private final Clock clock;
    private State state = State.CLOSED;
    private int failures;
    private Instant openedAt;
    private boolean probeInFlight;

    public CircuitBreaker(int failureThreshold, Duration openFor, Clock clock) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be >= 1");
        }
        this.failureThreshold = failureThreshold;
        this.openFor = openFor;
        this.clock = clock;
    }

    /** @return true when the call may go ahead (and then MUST be followed by success() or failure()). */
    public synchronized boolean tryAcquire() {
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (clock.instant().isBefore(openedAt.plus(openFor))) {
                    return false;
                }
                state = State.HALF_OPEN;
                probeInFlight = true;
                return true;
            default: // HALF_OPEN
                if (probeInFlight) {
                    return false;
                }
                probeInFlight = true;
                return true;
        }
    }

    public synchronized void success() {
        state = State.CLOSED;
        failures = 0;
        probeInFlight = false;
    }

    public synchronized void failure() {
        probeInFlight = false;
        if (state == State.HALF_OPEN) {
            open();
            return;
        }
        failures++;
        if (failures >= failureThreshold) {
            open();
        }
    }

    private void open() {
        state = State.OPEN;
        openedAt = clock.instant();
    }

    public synchronized State state() {
        return state;
    }
}
