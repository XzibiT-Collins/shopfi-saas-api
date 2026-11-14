package com.example.perfume_budget.theme;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThemeWriteRateLimiterTest {

    static final class MutableClock extends Clock {
        final AtomicLong millis = new AtomicLong(1_000_000);
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
        @Override public long millis() { return millis.get(); }
    }

    MutableClock clock = new MutableClock();

    @Test
    void allowsBurstUpToLimitThenRejectsWithRetryAfter() {
        ThemeWriteRateLimiter l = new ThemeWriteRateLimiter(30, clock);
        for (int i = 0; i < 30; i++) {
            assertThat(l.tryAcquire("a:x")).as("request %d", i + 1).isZero();
        }
        long retry = l.tryAcquire("a:x");
        assertThat(retry).isBetween(1L, 2L); // 30/min = one token per 2 s
    }

    @Test
    void refillsOverTime() {
        ThemeWriteRateLimiter l = new ThemeWriteRateLimiter(30, clock);
        for (int i = 0; i < 30; i++) {
            l.tryAcquire("a:x");
        }
        assertThat(l.tryAcquire("a:x")).isPositive();
        clock.millis.addAndGet(2_100);
        assertThat(l.tryAcquire("a:x")).isZero();
        assertThat(l.tryAcquire("a:x")).isPositive();
        clock.millis.addAndGet(120_000); // never exceeds capacity
        for (int i = 0; i < 30; i++) {
            assertThat(l.tryAcquire("a:x")).isZero();
        }
        assertThat(l.tryAcquire("a:x")).isPositive();
    }

    @Test
    void keysAreIndependent_tenantAndAdmin() {
        ThemeWriteRateLimiter l = new ThemeWriteRateLimiter(1, clock);
        assertThat(l.tryAcquire("acme:admin@x")).isZero();
        assertThat(l.tryAcquire("acme:admin@x")).isPositive();
        assertThat(l.tryAcquire("bravo:admin@x")).isZero();
        assertThat(l.tryAcquire("acme:other@x")).isZero();
    }

    @Test
    void mapIsBoundedUnderManyKeys() {
        ThemeWriteRateLimiter l = new ThemeWriteRateLimiter(30, clock);
        for (int i = 0; i < ThemeWriteRateLimiter.MAX_KEYS * 2; i++) {
            l.tryAcquire("t:" + i);
        }
        assertThat(l.size()).isLessThanOrEqualTo(ThemeWriteRateLimiter.MAX_KEYS);
    }

    @Test
    void idleBucketsAreEvicted() {
        ThemeWriteRateLimiter l = new ThemeWriteRateLimiter(30, clock);
        for (int i = 0; i < ThemeWriteRateLimiter.MAX_KEYS / 2; i++) {
            l.tryAcquire("t:" + i);
        }
        clock.millis.addAndGet(120_000);
        l.tryAcquire("new");
        assertThat(l.size()).isEqualTo(1);
    }

    @Test
    void rejectsNonPositiveLimit() {
        assertThatThrownBy(() -> new ThemeWriteRateLimiter(0, clock)).isInstanceOf(IllegalArgumentException.class);
    }
}
