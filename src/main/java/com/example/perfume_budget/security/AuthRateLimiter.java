package com.example.perfume_budget.security;

import com.example.perfume_budget.exception.RateLimitExceededException;
import com.example.perfume_budget.platform.service.TokenBucketRateLimiter;
import com.example.perfume_budget.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * Throttles unauthenticated, email-sending tenant endpoints (currently {@code POST /auth/resend-otp}) per client IP
 * and per tenant+email, using the bounded {@link TokenBucketRateLimiter}. The checks run on the request input only,
 * BEFORE any account lookup, so a throttled response is identical for known and unknown addresses. Tunables
 * ({@code application.auth.rate-limit.*}) default to production-safe values.
 */
@Component
public class AuthRateLimiter {

    private final TokenBucketRateLimiter resendOtpIp;
    private final TokenBucketRateLimiter resendOtpEmail;

    public AuthRateLimiter(
            @Value("${application.auth.rate-limit.resend-otp-ip.capacity:10}") int ipCapacity,
            @Value("${application.auth.rate-limit.resend-otp-ip.period-seconds:600}") int ipPeriodSeconds,
            @Value("${application.auth.rate-limit.resend-otp-email.capacity:3}") int emailCapacity,
            @Value("${application.auth.rate-limit.resend-otp-email.period-seconds:600}") int emailPeriodSeconds,
            @Value("${application.auth.rate-limit.max-tracked-keys:10000}") int maxKeys) {
        this.resendOtpIp = new TokenBucketRateLimiter(ipCapacity, Duration.ofSeconds(ipPeriodSeconds), maxKeys, System::nanoTime);
        this.resendOtpEmail = new TokenBucketRateLimiter(emailCapacity, Duration.ofSeconds(emailPeriodSeconds), maxKeys, System::nanoTime);
    }

    /** @param ipKey {@link ClientIps#rateLimitKey}; @throws RateLimitExceededException (429 + Retry-After) */
    public void checkResendOtp(String ipKey, String email) {
        check(resendOtpIp, ipKey == null ? "unknown" : ipKey);
        String slug = TenantContext.getSlug();
        String normalized = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        check(resendOtpEmail, (slug == null ? "-" : slug) + "|" + normalized);
    }

    private static void check(TokenBucketRateLimiter limiter, String key) {
        long retry = limiter.tryAcquire(key);
        if (retry > 0) {
            throw new RateLimitExceededException("Too many requests. Please try again later.", retry);
        }
    }
}
