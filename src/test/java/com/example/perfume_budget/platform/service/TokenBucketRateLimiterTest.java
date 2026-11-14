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

    // ---- PROV-02b AC2: hard bound, O(1)/amortised eviction, fail closed ---------------------------------------

    @Test
    void neverTracksMoreThanMaxKeys_andFailsClosedToSharedOverflowBucket() {
        AtomicLong now = new AtomicLong();
        TokenBucketRateLimiter l = new TokenBucketRateLimiter(1, Duration.ofHours(1), 100, now::get);
        for (int i = 0; i < 100; i++) {
            assertThat(l.tryAcquire("k" + i)).isZero(); // each consumes its only token, none is idle
        }
        assertThat(l.size()).isEqualTo(100);

        // cap reached: further unique keys are NOT tracked and share ONE overflow bucket (capacity 1 here)
        assertThat(l.tryAcquire("new-1")).isZero();
        assertThat(l.tryAcquire("new-2")).as("overflow bucket is shared and already spent").isPositive();
        assertThat(l.tryAcquire("new-3")).isPositive();
        assertThat(l.size()).isEqualTo(100);

        // tracked keys keep their own state: an existing debtor is still limited, not reset by the flood
        assertThat(l.tryAcquire("k0")).isPositive();
    }

    @Test
    void floodCannotEvictAKeyThatStillOwesTokens() {
        AtomicLong now = new AtomicLong();
        TokenBucketRateLimiter l = new TokenBucketRateLimiter(2, Duration.ofHours(1), 50, now::get);
        l.tryAcquire("victim");
        l.tryAcquire("victim"); // victim is now limited
        for (int i = 0; i < 10_000; i++) {
            l.tryAcquire("flood-" + i);
        }
        assertThat(l.size()).isLessThanOrEqualTo(50);
        assertThat(l.tryAcquire("victim")).as("flooding must not reset the limit").isPositive();
    }

    @Test
    void idleBucketsAreReclaimedByAccessOrder_soTheCapRecovers() {
        AtomicLong now = new AtomicLong();
        TokenBucketRateLimiter l = new TokenBucketRateLimiter(1, Duration.ofSeconds(10), 100, now::get);
        for (int i = 0; i < 100; i++) {
            l.tryAcquire("k" + i);
        }
        now.addAndGet(Duration.ofSeconds(11).toNanos()); // everything refilled -> idle
        assertThat(l.tryAcquire("fresh")).isZero();
        assertThat(l.size()).isEqualTo(1); // all idle heads dropped, "fresh" tracked
    }

    @Test
    void fiftyThousandUniqueKeys_stayBounded_andEveryCallMeetsATimeBudget() {
        TokenBucketRateLimiter l = new TokenBucketRateLimiter(5, Duration.ofMinutes(10), 10_000, System::nanoTime);
        // warm up the JIT so the budget measures steady state, not class loading
        for (int i = 0; i < 20_000; i++) {
            l.tryAcquire("warm-" + (i % 500));
        }
        long worst = 0;
        long total = System.nanoTime();
        for (int i = 0; i < 50_000; i++) {
            long t0 = System.nanoTime();
            l.tryAcquire("unique-" + i);
            worst = Math.max(worst, System.nanoTime() - t0);
        }
        total = System.nanoTime() - total;
        assertThat(l.size()).isLessThanOrEqualTo(10_000);
        // The vulnerable version measured ~3.7 ms per call (81 s total) at this size. Generous CI-safe budgets:
        assertThat(Duration.ofNanos(total)).as("total for 50k calls").isLessThan(Duration.ofSeconds(2));
        assertThat(Duration.ofNanos(total / 50_000)).as("mean per call").isLessThan(Duration.ofMillis(1));
        assertThat(Duration.ofNanos(worst)).as("worst single call (GC pauses allowed for)").isLessThan(Duration.ofMillis(250));
    }

    @Test
    void onboardingLimiter_respectsConfiguredMaxTrackedKeys() {
        OnboardingProperties p = new OnboardingProperties();
        p.setMaxTrackedKeys(3);
        p.setSignupIp(new OnboardingProperties.Limit(1, 3600));
        OnboardingRateLimiter l = new OnboardingRateLimiter(p);
        l.checkSignupIp("a");
        l.checkSignupIp("b");
        l.checkSignupIp("c");
        l.checkSignupIp("d"); // overflow bucket: first use allowed
        assertThatThrownBy(() -> l.checkSignupIp("e")).isInstanceOf(RateLimitExceededException.class); // shared, spent
    }

    @Test
    void rejectsNonPositiveMaxKeys() {
        assertThatThrownBy(() -> new TokenBucketRateLimiter(1, Duration.ofSeconds(1), 0, () -> 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
