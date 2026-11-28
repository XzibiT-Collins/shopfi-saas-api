package com.example.perfume_budget.platform.billing;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The clock of the BILLING code only (billing.md 19.2). It is deliberately NOT a {@link Clock} bean: the global
 * {@code Clock} (JWT expiry, OTP, rate limits, the paywall) must stay the real time, even in dev. It reads the global clock
 * plus an offset that only {@code DevBillingController} (dev profile, never under {@code prod}) can change; in every other
 * profile no code path ever sets the offset, so {@link #now()} equals the global clock.
 */
@Component
public class BillingClock {

    private final Clock base;
    private final AtomicLong offsetSeconds = new AtomicLong();

    public BillingClock(Clock base) {
        this.base = base;
    }

    public Instant now() {
        return base.instant().plusSeconds(offsetSeconds.get());
    }

    public long offsetSeconds() {
        return offsetSeconds.get();
    }

    /** Dev only (called by DevBillingController). */
    public void advanceSeconds(long seconds) {
        offsetSeconds.addAndGet(seconds);
    }

    /** Dev only: moves the billing clock to {@code target} (may move it back). */
    public void moveTo(Instant target) {
        offsetSeconds.set(target.getEpochSecond() - base.instant().getEpochSecond());
    }

    /** Dev only. */
    public void reset() {
        offsetSeconds.set(0);
    }
}
