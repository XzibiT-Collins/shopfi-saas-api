package com.example.perfume_budget.service;

import com.example.perfume_budget.exception.RateLimitExceededException;
import com.example.perfume_budget.platform.service.TokenBucketRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * VIEW-01: per client key (IPv4, or IPv6 /64), across ALL tenants, so one address cannot spread its allowance over many
 * shops. Burst and per-minute refill are the same number. Bounded and fail closed: see {@link TokenBucketRateLimiter}.
 */
@Component
public class ProductViewRateLimiter {

    private final TokenBucketRateLimiter bucket;

    public ProductViewRateLimiter(@Value("${catalog.views.rate-limit-burst:30}") int burstPerMinute,
                                  @Value("${catalog.views.rate-limit-max-keys:10000}") int maxKeys) {
        this.bucket = new TokenBucketRateLimiter(Math.max(1, burstPerMinute), Duration.ofMinutes(1),
                Math.max(1, maxKeys), System::nanoTime);
    }

    /** @throws RateLimitExceededException (429 + Retry-After) when the client key is out of tokens */
    public void check(String clientKey) {
        long retry = bucket.tryAcquire(clientKey);
        if (retry > 0) {
            throw new RateLimitExceededException("Too many requests", retry);
        }
    }

    /** Test support only. */
    public void reset() {
        bucket.reset();
    }
}
