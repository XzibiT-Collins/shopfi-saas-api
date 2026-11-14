package com.example.perfume_budget.platform.auth;

import com.example.perfume_budget.exception.RateLimitExceededException;
import com.example.perfume_budget.platform.service.OnboardingProperties.Limit;
import com.example.perfume_budget.platform.service.TokenBucketRateLimiter;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * Per-IP and per-email throttles for platform login/refresh (bounded {@link TokenBucketRateLimiter}s). The email
 * bucket applies to unknown emails exactly like known ones, so a 429 reveals nothing about existence.
 */
@Component
public class PlatformLoginRateLimiter {

    private static final String MESSAGE = "Too many attempts. Please try again later.";

    private final TokenBucketRateLimiter loginIp;
    private final TokenBucketRateLimiter loginEmail;
    private final TokenBucketRateLimiter refreshIp;

    public PlatformLoginRateLimiter(PlatformAuthProperties p) {
        this.loginIp = build(p.getLoginIp(), p.getMaxTrackedKeys());
        this.loginEmail = build(p.getLoginEmail(), p.getMaxTrackedKeys());
        this.refreshIp = build(p.getRefreshIp(), p.getMaxTrackedKeys());
    }

    private static TokenBucketRateLimiter build(Limit l, int maxKeys) {
        return new TokenBucketRateLimiter(l.getCapacity(), Duration.ofSeconds(l.getPeriodSeconds()), maxKeys, System::nanoTime);
    }

    public void checkLoginIp(String ipKey) {
        check(loginIp, ipKey);
    }

    public void checkLoginEmail(String email) {
        check(loginEmail, email == null ? "" : email.strip().toLowerCase(Locale.ROOT));
    }

    public void checkRefreshIp(String ipKey) {
        check(refreshIp, ipKey);
    }

    private static void check(TokenBucketRateLimiter limiter, String key) {
        long retry = limiter.tryAcquire(key == null ? "unknown" : key);
        if (retry > 0) {
            throw new RateLimitExceededException(MESSAGE, retry);
        }
    }
}
