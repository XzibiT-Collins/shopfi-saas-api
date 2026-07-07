package com.example.perfume_budget.exception;

import java.util.ArrayList;
import java.util.List;

public class ValidationException extends RuntimeException {
    private final List<String> errors;

    public ValidationException(List<String> errors) {
        super("Validation failed");
        this.errors = new ArrayList<>(errors);
    }

    public List<String> getErrors() {
        return new ArrayList<>(errors);
    }
}
