package com.example.perfume_budget.platform.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Tunables for tenant onboarding (prefix {@code onboarding}). Defaults are production-safe. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "onboarding")
public class OnboardingProperties {

    private int otpValidMinutes = 10;
    private int maxOtpAttempts = 5;
    private int lockoutMinutes = 15;
    /** PENDING signups older than this are purged when someone signs up with the same slug or email. */
    private int pendingTtlHours = 24;
    /** Scheme and admin path used to build the ACTIVE-status URLs from {@code tenant.base-domain}. */
    private String publicScheme = "https";
    private String adminLoginPath = "/admin/login";

    private Limit signupIp = new Limit(5, 3600);
    private Limit signupEmail = new Limit(3, 3600);
    private Limit verifyIp = new Limit(20, 600);
    private Limit verifyEmail = new Limit(10, 600);
    private Limit statusIp = new Limit(60, 60);

    /** {@code capacity} requests per {@code periodSeconds} (burst = capacity, continuous refill). */
    @Getter
    @Setter
    public static class Limit {
        private int capacity;
        private long periodSeconds;

        public Limit() {
        }

        public Limit(int capacity, long periodSeconds) {
            this.capacity = capacity;
            this.periodSeconds = periodSeconds;
        }
    }
}
