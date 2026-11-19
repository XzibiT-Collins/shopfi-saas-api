package com.example.perfume_budget.platform.domain;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * DOMAIN-03: when a VERIFIED domain is checked next. Base delay plus a random jitter of up to
 * {@code jitter-percent} of it, so domains verified together do not stay in lock-step (no thundering herd).
 * Shared by the job and by {@link CustomDomainService#verify} (a manual verification also reschedules the domain).
 */
@Component
public class ReverifySchedule {

    private final CustomDomainReverifyProperties props;
    private volatile DoubleSupplier random = () -> ThreadLocalRandom.current().nextDouble();

    public ReverifySchedule(CustomDomainReverifyProperties props) {
        this.props = props;
    }

    /** Test support: deterministic jitter source returning values in [0, 1). */
    void setRandom(DoubleSupplier random) {
        this.random = random;
    }

    public Instant afterOk(Instant now) {
        return now.plus(jittered(props.getInterval()));
    }

    public Instant afterMiss(Instant now) {
        return now.plus(jittered(props.getMissRetryInterval()));
    }

    public Instant afterDnsError(Instant now) {
        return now.plus(jittered(props.getErrorRetryInterval()));
    }

    private Duration jittered(Duration base) {
        Duration b = base == null || base.isNegative() || base.isZero() ? Duration.ofMinutes(1) : base;
        double u = Math.min(Math.max(random.getAsDouble(), 0.0), 0.999999);
        long extra = (long) (b.toMillis() * Math.max(0, props.getJitterPercent()) / 100.0 * u);
        return b.plusMillis(extra);
    }
}
