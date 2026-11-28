package com.example.perfume_budget.platform.billing;

import com.example.perfume_budget.exception.RateLimitExceededException;
import com.example.perfume_budget.platform.auth.PlatformPrincipal;
import com.example.perfume_budget.platform.service.TokenBucketRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Per platform-admin throttles of the billing console (billing.md section 18, token buckets per JVM): credentials
 * verification (a Paystack call: {@code platform-verify-credentials}, 5 per 600 s) and every settings or credentials
 * write ({@code platform-settings-write}, 30 per 600 s). A throttled request is 429 with {@code Retry-After}.
 */
@Component
public class BillingAdminThrottle {

    private final TokenBucketRateLimiter verify;
    private final TokenBucketRateLimiter write;

    public BillingAdminThrottle(
            @Value("${billing.rate.platform-verify-credentials.capacity:5}") int verifyCapacity,
            @Value("${billing.rate.platform-verify-credentials.period-seconds:600}") int verifyPeriod,
            @Value("${billing.rate.platform-settings-write.capacity:30}") int writeCapacity,
            @Value("${billing.rate.platform-settings-write.period-seconds:600}") int writePeriod) {
        this.verify = new TokenBucketRateLimiter(Math.max(1, verifyCapacity), Duration.ofSeconds(Math.max(1, verifyPeriod)));
        this.write = new TokenBucketRateLimiter(Math.max(1, writeCapacity), Duration.ofSeconds(Math.max(1, writePeriod)));
    }

    public void verifyCredentials(PlatformPrincipal actor) {
        take(verify, actor);
    }

    public void write(PlatformPrincipal actor) {
        take(write, actor);
    }

    private static void take(TokenBucketRateLimiter limiter, PlatformPrincipal actor) {
        long wait = limiter.tryAcquire("admin:" + actor.id());
        if (wait > 0) {
            throw new RateLimitExceededException("Too many requests. Please try again later.", wait);
        }
    }

    /** Test support only: forget every bucket. */
    public void reset() {
        verify.reset();
        write.reset();
    }
}
