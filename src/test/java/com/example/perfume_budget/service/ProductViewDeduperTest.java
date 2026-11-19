package com.example.perfume_budget.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** VIEW-01: dedupe window, tenant/product/client separation and the memory bound. */
class ProductViewDeduperTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        @Override public long millis() { return now.get(); }
    };

    private ProductViewDeduper deduper(int max) {
        return new ProductViewDeduper(clock, 30, max);
    }

    @Test
    void secondViewInsideThirtyMinutesIsNotCounted_andTheWindowRunsFromTheFirstView() {
        ProductViewDeduper d = deduper(100);
        assertThat(d.firstView("acme", 1, "1.2.3.4")).isTrue();
        now.addAndGet(29 * 60_000L);
        assertThat(d.firstView("acme", 1, "1.2.3.4")).as("repeat at 29 min").isFalse();
        now.addAndGet(60_000L + 1);
        assertThat(d.firstView("acme", 1, "1.2.3.4")).as("a repeat does not extend the window: counted again after 30 min").isTrue();
    }

    @Test
    void theWindowBoundaryIsExactlyThirtyMinutes_oneMillisecondEarlyIsStillTheSameView() {
        ProductViewDeduper d = deduper(100);
        assertThat(d.firstView("acme", 1, "ip")).isTrue();
        now.addAndGet(30 * 60_000L - 1);
        assertThat(d.firstView("acme", 1, "ip")).as("29:59.999").isFalse();
        now.addAndGet(1);
        assertThat(d.firstView("acme", 1, "ip")).as("30:00.000 is a new window").isTrue();
        now.addAndGet(30 * 60_000L - 1);
        assertThat(d.firstView("acme", 1, "ip")).as("the new window runs from the recount").isFalse();
    }

    @Test
    void tenantProductAndClientAreAllPartOfTheKey() {
        ProductViewDeduper d = deduper(100);
        assertThat(d.firstView("acme", 1, "ip")).isTrue();
        assertThat(d.firstView("bravo", 1, "ip")).as("same product id and client in another tenant").isTrue();
        assertThat(d.firstView("acme", 2, "ip")).as("another product").isTrue();
        assertThat(d.firstView("acme", 1, "ip2")).as("another client").isTrue();
        assertThat(d.firstView("acme", 1, "ip")).isFalse();
        assertThat(d.firstView("bravo", 1, "ip")).isFalse();
    }

    @Test
    void theStoreIsBounded_oldestEntryIsEvictedFirst() {
        ProductViewDeduper d = deduper(3);
        for (int i = 0; i < 1000; i++) {
            d.firstView("acme", i, "ip");
            assertThat(d.size()).isLessThanOrEqualTo(3);
        }
        assertThat(d.size()).isEqualTo(3);
        assertThat(d.firstView("acme", 999, "ip")).as("newest is still remembered").isFalse();
        assertThat(d.firstView("acme", 0, "ip")).as("oldest was evicted").isTrue();
    }

    @Test
    void expiredEntriesAreDroppedFromTheHead() {
        ProductViewDeduper d = deduper(100);
        for (int i = 0; i < 10; i++) {
            d.firstView("acme", i, "ip");
        }
        now.addAndGet(31 * 60_000L);
        d.firstView("acme", 100, "ip");
        assertThat(d.size()).isEqualTo(1);
    }
}
