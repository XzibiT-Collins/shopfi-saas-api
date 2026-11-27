package com.example.perfume_budget.platform.plan;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A plan rule or request-content check failed (plans.md sections 3.3, 7, 8, 11): answered as the existing bean-validation
 * 400 shape {@code {"description": "Validation failed", "data": {"<field path>": "<message>"}}} by
 * {@code PlanExceptionHandler}. Nothing was written (thrown before any write, inside the transaction).
 */
public class PlanValidationException extends RuntimeException {

    private final Map<String, String> fields;

    public PlanValidationException(Map<String, String> fields) {
        super("Validation failed", null, false, false);
        this.fields = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    public PlanValidationException(String field, String message) {
        this(Map.of(field, message));
    }

    public Map<String, String> fields() {
        return fields;
    }
}
