package com.example.perfume_budget.exception;

public class BadRequestException extends RuntimeException {
    /** API-RD-11a: optional stable machine code, rendered as {@code data.code}; null keeps the historic {@code data: null}. */
    private final String code;

    public BadRequestException(String message) {
        this(message, null);
    }

    public BadRequestException(String message, String code) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
