package com.example.perfume_budget.platform.service;

import com.example.perfume_budget.exception.RateLimitExceededException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenBucketRateLimiterTest {

    @Test
    void allowsBurstThenRejectsAndRefillsOverTime() {
        AtomicLong now = new AtomicLong();
        TokenBucketRateLimiter l = new TokenBucketRateLimiter(3, Duration.ofSeconds(30), now::get);
        assertThat(l.tryAcquire("k")).isZero();
        assertThat(l.tryAcquire("k")).isZero();
        assertThat(l.tryAcquire("k")).isZero();
        long retry = l.tryAcquire("k");
        assertThat(retry).isBetween(1L, 10L);

        now.addAndGet(Duration.ofSeconds(10).toNanos()); // one token refilled
        assertThat(l.tryAcquire("k")).isZero();
        assertThat(l.tryAcquire("k")).isPositive();
    }

    @Test
    void keysAreIndependent() {
        TokenBucketRateLimiter l = new TokenBucketRateLimiter(1, Duration.ofHours(1), () -> 0L);
        assertThat(l.tryAcquire("a")).isZero();
        assertThat(l.tryAcquire("a")).isPositive();
        assertThat(l.tryAcquire("b")).isZero();
    }

    @Test
    void neverExceedsCapacityAfterLongIdle() {
        AtomicLong now = new AtomicLong();
        TokenBucketRateLimiter l = new TokenBucketRateLimiter(2, Duration.ofSeconds(10), now::get);
        now.addAndGet(Duration.ofDays(1).toNanos());
        assertThat(l.tryAcquire("k")).isZero();
        assertThat(l.tryAcquire("k")).isZero();
        assertThat(l.tryAcquire("k")).isPositive();
    }

    @Test
    void idleBucketsAreEvictedPastThreshold() {
        AtomicLong now = new AtomicLong();
        TokenBucketRateLimiter l = new TokenBucketRateLimiter(1, Duration.ofSeconds(1), now::get);
        for (int i = 0; i <= 10_000; i++) {
            l.tryAcquire("k" + i); // each consumes its only token
        }
        now.addAndGet(Duration.ofSeconds(5).toNanos()); // all refilled -> idle
        l.tryAcquire("trigger");
        assertThat(l.size()).isLessThan(100);
    }

    @Test
    void rejectsInvalidConfig() {
        assertThatThrownBy(() -> new TokenBucketRateLimiter(0, Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucketRateLimiter(1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onboardingLimiter_perIpAndPerEmail_throw429Exception() {
        OnboardingProperties p = new OnboardingProperties();
        p.setSignupIp(new OnboardingProperties.Limit(2, 3600));
        p.setSignupEmail(new OnboardingProperties.Limit(1, 3600));
        OnboardingRateLimiter l = new OnboardingRateLimiter(p);

        l.checkSignupEmail("A@x.com");
        assertThatThrownBy(() -> l.checkSignupEmail(" a@X.com ")) // normalised: same bucket
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(e -> assertThat(((RateLimitExceededException) e).getRetryAfterSeconds()).isPositive());

        l.checkSignupIp("1.1.1.1");
        l.checkSignupIp("1.1.1.1");
        assertThatThrownBy(() -> l.checkSignupIp("1.1.1.1")).isInstanceOf(RateLimitExceededException.class);
        l.checkSignupIp("2.2.2.2");
    }
}
