package com.example.perfume_budget.exception;

import org.springframework.http.HttpStatus;

/**
 * An {@link ApiCodeException} (429 or 503) that tells the client when to retry: rendered like any ApiCodeException plus a
 * {@code Retry-After} header in seconds (DOMAIN-02: verification rate limit, DNS unavailable).
 */
public class RetryAfterApiCodeException extends ApiCodeException {
    private final long retryAfterSeconds;

    public RetryAfterApiCodeException(HttpStatus status, String code, String message, long retryAfterSeconds) {
        super(status, code, message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
