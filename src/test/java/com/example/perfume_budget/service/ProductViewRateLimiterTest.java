package com.example.perfume_budget.service;

import com.example.perfume_budget.exception.RateLimitExceededException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** VIEW-01: per client key, across tenants, bounded and fail closed. */
class ProductViewRateLimiterTest {

    @Test
    void burstThen429WithRetryAfter_otherClientsAreIndependent() {
        ProductViewRateLimiter l = new ProductViewRateLimiter(3, 100);
        for (int i = 0; i < 3; i++) {
            l.check("1.1.1.1");
        }
        assertThatThrownBy(() -> l.check("1.1.1.1")).isInstanceOfSatisfying(RateLimitExceededException.class,
                e -> assertThat(e.getRetryAfterSeconds()).isBetween(1L, 60L));
        l.check("2.2.2.2");
    }

    @Test
    void aFloodOfNewKeysFailsClosedToOneSharedBucket() {
        ProductViewRateLimiter l = new ProductViewRateLimiter(2, 2);
        l.check("a");
        l.check("b");
        // table full (both buckets are in debt): the next new keys share ONE overflow bucket of burst 2
        l.check("c");
        l.check("d");
        assertThatThrownBy(() -> l.check("e")).isInstanceOf(RateLimitExceededException.class);
    }
}
