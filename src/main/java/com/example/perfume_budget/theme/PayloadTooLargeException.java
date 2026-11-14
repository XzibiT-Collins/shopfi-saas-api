package com.example.perfume_budget.theme;

/** Theme JSON or an uploaded file exceeds its limit. Maps to HTTP 413. */
public class PayloadTooLargeException extends RuntimeException {
    public PayloadTooLargeException(String message) {
        super(message);
    }
}
