package com.example.perfume_budget.exception;

import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An API error that carries a stable machine-readable {@code code} (docs/api/checkout.md). Rendered as
 * {@code {"description": <message>, "data": {"code": <code>, ...extra}}} with the given HTTP status.
 */
public class ApiCodeException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> extra;

    public ApiCodeException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiCodeException(HttpStatus status, String code, String message, Map<String, Object> extra) {
        super(message);
        this.status = status;
        this.code = code;
        this.extra = extra;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> body() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.putAll(extra);
        return out;
    }
}
