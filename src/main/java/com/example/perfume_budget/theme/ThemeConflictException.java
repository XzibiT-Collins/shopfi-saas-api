package com.example.perfume_budget.theme;

/** The expected version (If-Match) is stale, or a concurrent write won. Maps to HTTP 412. */
public class ThemeConflictException extends RuntimeException {
    public ThemeConflictException(String message) {
        super(message);
    }
}
