package com.example.perfume_budget.platform.auth;

import com.example.perfume_budget.platform.service.OnboardingProperties.Limit;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Tunables for platform super-admin auth (prefix {@code platform.auth}). Defaults are production-safe. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "platform.auth")
public class PlatformAuthProperties {

    /** Platform sessions are deliberately shorter lived than tenant sessions. */
    private Duration accessTtl = Duration.ofMinutes(15);
    private Duration refreshTtl = Duration.ofHours(12);

    /** Every login attempt (success or failure) consumes a token. */
    private Limit loginIp = new Limit(10, 300);
    private Limit loginEmail = new Limit(5, 900);
    private Limit refreshIp = new Limit(30, 300);
    private int maxTrackedKeys = 10_000;
}
