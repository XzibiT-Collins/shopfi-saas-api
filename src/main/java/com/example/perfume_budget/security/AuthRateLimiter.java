package com.example.perfume_budget.security;

import com.example.perfume_budget.exception.RateLimitExceededException;
import com.example.perfume_budget.platform.service.TokenBucketRateLimiter;
import com.example.perfume_budget.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * Throttles unauthenticated, email-sending tenant endpoints ({@code POST /auth/resend-otp},
 * {@code POST /auth/forgot-password}, {@code POST /auth/resend-login-otp}) with the bounded
 * {@link TokenBucketRateLimiter}. The checks run on the request input only, BEFORE any account lookup, so a throttled
 * response is identical for known and unknown addresses. Tunables ({@code application.auth.rate-limit.*}) default to
 * production-safe values.
 *
 * <p>AUTH-02 AC1: the per-email budget is keyed on client IP + tenant + email, so a stranger cannot spend a victim's
 * resend budget from another address (the victim keeps their own bucket). A separate, looser per-tenant+email CEILING
 * bounds mail-bombing a victim from many addresses; it is 10 per period by default (the same lock-out class remains
 * only for an attacker holding more than that many distinct client addresses).
 */
@Component
public class AuthRateLimiter {

    /** One email-sending endpoint: per IP, per IP+email, and the per-email ceiling. */
    private static final class Throttle {
        final TokenBucketRateLimiter ip;
        final TokenBucketRateLimiter ipEmail;
        final TokenBucketRateLimiter emailCeiling;

        Throttle(int ipCap, int ipPeriod, int emailCap, int emailPeriod, int ceilingCap, int maxKeys) {
            this.ip = new TokenBucketRateLimiter(ipCap, Duration.ofSeconds(ipPeriod), maxKeys, System::nanoTime);
            this.ipEmail = new TokenBucketRateLimiter(emailCap, Duration.ofSeconds(emailPeriod), maxKeys, System::nanoTime);
            this.emailCeiling = new TokenBucketRateLimiter(ceilingCap, Duration.ofSeconds(emailPeriod), maxKeys, System::nanoTime);
        }
    }

    private final Throttle resendOtp;
    private final Throttle forgotPassword;
    private final TokenBucketRateLimiter resendLoginOtpIp;

    @Autowired
    public AuthRateLimiter(
            @Value("${application.auth.rate-limit.resend-otp-ip.capacity:10}") int ipCapacity,
            @Value("${application.auth.rate-limit.resend-otp-ip.period-seconds:600}") int ipPeriodSeconds,
            @Value("${application.auth.rate-limit.resend-otp-email.capacity:3}") int emailCapacity,
            @Value("${application.auth.rate-limit.resend-otp-email.period-seconds:600}") int emailPeriodSeconds,
            @Value("${application.auth.rate-limit.max-tracked-keys:10000}") int maxKeys,
            @Value("${application.auth.rate-limit.resend-otp-email-ceiling.capacity:10}") int ceilingCapacity,
            @Value("${application.auth.rate-limit.forgot-password-ip.capacity:10}") int forgotIpCapacity,
            @Value("${application.auth.rate-limit.forgot-password-email.capacity:3}") int forgotEmailCapacity,
            @Value("${application.auth.rate-limit.resend-login-otp-ip.capacity:10}") int loginOtpIpCapacity) {
        this.resendOtp = new Throttle(ipCapacity, ipPeriodSeconds, emailCapacity, emailPeriodSeconds, ceilingCapacity, maxKeys);
        this.forgotPassword = new Throttle(forgotIpCapacity, ipPeriodSeconds, forgotEmailCapacity, emailPeriodSeconds,
                ceilingCapacity, maxKeys);
        this.resendLoginOtpIp = new TokenBucketRateLimiter(loginOtpIpCapacity, Duration.ofSeconds(ipPeriodSeconds), maxKeys,
                System::nanoTime);
    }

    public AuthRateLimiter(int ipCapacity, int ipPeriodSeconds, int emailCapacity, int emailPeriodSeconds, int maxKeys) {
        this(ipCapacity, ipPeriodSeconds, emailCapacity, emailPeriodSeconds, maxKeys, 10, 10, 3, 10);
    }

    /** @param ipKey {@link ClientIps#rateLimitKey}; @throws RateLimitExceededException (429 + Retry-After) */
    public void checkResendOtp(String ipKey, String email) {
        check(resendOtp, ipKey, email);
    }

    /** AUTH-02 AC3: {@code POST /auth/forgot-password}. */
    public void checkForgotPassword(String ipKey, String email) {
        check(forgotPassword, ipKey, email);
    }

    /** AUTH-02 AC3: {@code POST /auth/resend-login-otp} (the challenge token names no email: per IP only). */
    public void checkResendLoginOtp(String ipKey) {
        check(resendLoginOtpIp, ipKey == null ? "unknown" : ipKey);
    }

    private static void check(Throttle t, String ipKey, String email) {
        String ip = ipKey == null ? "unknown" : ipKey;
        check(t.ip, ip);
        String slug = TenantContext.getSlug();
        String emailKey = (slug == null ? "-" : slug) + "|" + (email == null ? "" : email.strip().toLowerCase(Locale.ROOT));
        check(t.ipEmail, ip + "|" + emailKey);
        check(t.emailCeiling, emailKey);
    }

    private static void check(TokenBucketRateLimiter limiter, String key) {
        long retry = limiter.tryAcquire(key);
        if (retry > 0) {
            throw new RateLimitExceededException("Too many requests. Please try again later.", retry);
        }
    }
}
