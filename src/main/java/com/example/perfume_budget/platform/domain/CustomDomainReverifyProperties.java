package com.example.perfume_budget.platform.domain;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** {@code tenant.custom-domain.reverify.*} (DOMAIN-03); env names in docs/api/custom-domains.md. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "tenant.custom-domain.reverify")
public class CustomDomainReverifyProperties {

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
}
