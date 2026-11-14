package com.example.perfume_budget.service;

import com.example.perfume_budget.exception.RateLimitExceededException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CouponPreviewRateLimiterTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    @Test
    void blocksAfterAllowanceWith429SemanticsAndRetryAfter() {
        CouponPreviewRateLimiter l = new CouponPreviewRateLimiter(clock, 3);
        for (int i = 0; i < 3; i++) l.check("acme", 1L);
        RateLimitExceededException e = assertThrows(RateLimitExceededException.class, () -> l.check("acme", 1L));
        assertTrue(e.getRetryAfterSeconds() >= 1 && e.getRetryAfterSeconds() <= 60);
    }

    @Test
    void isScopedPerTenantAndCustomerAndResetsAfterTheWindow() {
        CouponPreviewRateLimiter l = new CouponPreviewRateLimiter(clock, 1);
        l.check("acme", 1L);
        assertThrows(RateLimitExceededException.class, () -> l.check("acme", 1L));
        l.check("acme", 2L);
        l.check("bravo", 1L);
        now.set(now.get().plusSeconds(61));
        l.check("acme", 1L);
    }
}
