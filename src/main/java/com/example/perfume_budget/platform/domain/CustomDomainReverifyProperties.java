package com.example.perfume_budget.platform.domain;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** {@code tenant.custom-domain.reverify.*} (DOMAIN-03); env names in docs/api/custom-domains.md. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "tenant.custom-domain.reverify")
@Slf4j
public class CustomDomainReverifyProperties implements InitializingBean {

    /** The scheduler runs only when true (off by default; on in the prod profile). */
    private boolean enabled = false;

    /** Delay between two job rounds (each round checks at most {@link #batchSize} domains). */
    private Duration tickInterval = Duration.ofMinutes(1);

    /** Delay before the first round after start-up. */
    private Duration initialDelay = Duration.ofMinutes(2);

    /** Domains checked per round, at most. */
    private int batchSize = 10;

    /** Time between two checks of a domain that was found OK. */
    private Duration interval = Duration.ofHours(24);

    /** Random extra delay added to every schedule, as a percentage of the base delay (0 = none). */
    private int jitterPercent = 20;

    /** Time to the next check after a definite miss (must be well below {@link #grace}). */
    private Duration missRetryInterval = Duration.ofHours(6);

    /** Time to the next check after DNS gave no definite answer (timeout/SERVFAIL). */
    private Duration errorRetryInterval = Duration.ofMinutes(30);

    /** Consecutive definite misses needed (together with {@link #grace}) before VERIFIED becomes FAILED. */
    private int missesRequired = 3;

    /** Time that must pass between the first miss of a streak and the downgrade. */
    private Duration grace = Duration.ofHours(24);

    /** How long a claimed row stays reserved for the claiming instance (a crashed instance's rows return after it). */
    private Duration lease = Duration.ofMinutes(10);

    /** DNS lookups per minute this job may use (on top of the shared global verify budget; per JVM). */
    private int dnsPerMinute = 20;

    /** Startup validation: a nonsense value fails the start instead of silently disabling or hammering DNS. */
    @Override
    public void afterPropertiesSet() {
        validate();
    }

    void validate() {
        positive("tick-interval", tickInterval);
        positive("interval", interval);
        positive("miss-retry-interval", missRetryInterval);
        positive("error-retry-interval", errorRetryInterval);
        positive("lease", lease);
        if (initialDelay == null || initialDelay.isNegative()) {
            throw bad("initial-delay", "must not be negative");
        }
        if (grace == null || grace.isNegative()) {
            throw bad("grace", "must not be negative");
        }
        if (batchSize < 1) {
            throw bad("batch-size", "must be at least 1");
        }
        if (missesRequired < 1) {
            throw bad("misses-required", "must be at least 1");
        }
        if (dnsPerMinute < 1) {
            throw bad("dns-per-minute", "must be at least 1");
        }
        if (jitterPercent < 0 || jitterPercent > 100) {
            throw bad("jitter-percent", "must be between 0 and 100");
        }
        if (missesRequired == 1 && grace.isZero()) {
            log.warn("Custom domain re-verification: misses-required=1 with grace=0 downgrades a domain on its first "
                    + "definite miss (no tolerance for a bad answer from a resolver)");
        }
    }

    private static void positive(String name, Duration d) {
        if (d == null || d.isZero() || d.isNegative()) {
            throw bad(name, "must be a positive duration");
        }
    }

    private static IllegalStateException bad(String name, String why) {
        return new IllegalStateException("tenant.custom-domain.reverify." + name + " " + why);
    }
}
