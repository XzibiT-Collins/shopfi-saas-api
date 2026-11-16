package com.example.perfume_budget.service;

import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** SEC-03c residual: a product view counts once per (tenant, product, client) per window. */
class ProductViewDeduplicatorTest {

    private final AtomicLong now = new AtomicLong(1_000);
    private final ProductViewDeduplicator dedupe = new ProductViewDeduplicator(60, 3, now::get);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void sameClientAndProductCountsOncePerWindow() {
        assertThat(dedupe.firstView(1L, "1.2.3.4")).isTrue();
        assertThat(dedupe.firstView(1L, "1.2.3.4")).isFalse();
        now.addAndGet(TimeUnit.SECONDS.toNanos(60) - 1);
        assertThat(dedupe.firstView(1L, "1.2.3.4")).as("still inside the window").isFalse();
        now.addAndGet(1);
        assertThat(dedupe.firstView(1L, "1.2.3.4")).as("window elapsed").isTrue();
    }

    @Test
    void otherClientProductOrTenantCountsSeparately() {
        TenantContext.set(TenantIdentity.tenant("acme", "tenant_acme"));
        assertThat(dedupe.firstView(1L, "1.2.3.4")).isTrue();
        assertThat(dedupe.firstView(1L, "5.6.7.8")).isTrue();
        assertThat(dedupe.firstView(2L, "1.2.3.4")).isTrue();
        TenantContext.set(TenantIdentity.tenant("bravo", "tenant_bravo"));
        assertThat(dedupe.firstView(1L, "1.2.3.4")).isTrue();
    }

    @Test
    void boundedAndNullSafe() {
        assertThat(dedupe.firstView(null, "x")).isFalse();
        assertThat(dedupe.firstView(1L, null)).isTrue();
        assertThat(dedupe.firstView(1L, null)).isFalse();
        dedupe.firstView(2L, "a");
        dedupe.firstView(3L, "a");
        dedupe.firstView(4L, "a"); // evicts the oldest (1L, null)
        assertThat(dedupe.firstView(1L, null)).as("evicted entries may count again").isTrue();
    }
}
