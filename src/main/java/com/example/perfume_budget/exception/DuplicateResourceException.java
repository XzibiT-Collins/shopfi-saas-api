package com.example.perfume_budget.exception;

public class DuplicateResourceException extends RuntimeException {
    /** API-RD-11a: optional stable machine code, rendered as {@code data.code}; null keeps the historic {@code data: null}. */
    private final String code;

    public DuplicateResourceException(String message) {
        this(message, null);
    }

    public DuplicateResourceException(String message, String code) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
