package com.example.perfume_budget.theme;

/** Per-admin PUT rate limit exceeded. Maps to HTTP 429 with {@code Retry-After}. */
public class ThemeRateLimitedException extends RuntimeException {
    private final long retryAfterSeconds;

    public ThemeRateLimitedException(long retryAfterSeconds) {
        super("Too many theme updates");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
