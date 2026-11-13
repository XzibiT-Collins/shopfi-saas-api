package com.example.perfume_budget.platform.service;

import com.example.perfume_budget.exception.RateLimitExceededException;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Per-IP and per-email limits for the public onboarding endpoints. Throws {@link RateLimitExceededException}. */
@Component
public class OnboardingRateLimiter {

    private final TokenBucketRateLimiter signupIp;
    private final TokenBucketRateLimiter signupEmail;
    private final TokenBucketRateLimiter verifyIp;
    private final TokenBucketRateLimiter verifyEmail;
    private final TokenBucketRateLimiter statusIp;

    public OnboardingRateLimiter(OnboardingProperties p) {
        this.signupIp = build(p.getSignupIp());
        this.signupEmail = build(p.getSignupEmail());
        this.verifyIp = build(p.getVerifyIp());
        this.verifyEmail = build(p.getVerifyEmail());
        this.statusIp = build(p.getStatusIp());
    }

    private static TokenBucketRateLimiter build(OnboardingProperties.Limit l) {
        return new TokenBucketRateLimiter(l.getCapacity(), Duration.ofSeconds(l.getPeriodSeconds()));
    }

    public void checkSignupIp(String ip) {
        check(signupIp, ip);
    }

    public void checkSignupEmail(String email) {
        check(signupEmail, normalize(email));
    }

    public void checkVerifyIp(String ip) {
        check(verifyIp, ip);
    }

    public void checkVerifyEmail(String email) {
        check(verifyEmail, normalize(email));
    }

    public void checkStatusIp(String ip) {
        check(statusIp, ip);
    }

    private static String normalize(String email) {
        return email == null ? "" : email.strip().toLowerCase(java.util.Locale.ROOT);
    }

    private static void check(TokenBucketRateLimiter limiter, String key) {
        long retry = limiter.tryAcquire(key == null ? "unknown" : key);
        if (retry > 0) {
            throw new RateLimitExceededException("Too many requests. Please try again later.", retry);
        }
    }
}
