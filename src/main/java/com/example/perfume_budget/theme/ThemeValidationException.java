package com.example.perfume_budget.theme;

import java.util.List;

/** Theme (or branding upload) failed validation. Maps to HTTP 400 with {@code data:[{path,code,message}]}. */
public class ThemeValidationException extends RuntimeException {
    private final transient List<ThemeFieldError> errors;

    public ThemeValidationException(List<ThemeFieldError> errors) {
        super("Theme validation failed");
        this.errors = List.copyOf(errors);
    }

    public ThemeValidationException(ThemeFieldError error) {
        this(List.of(error));
    }

    public List<ThemeFieldError> getErrors() {
        return errors;
    }
}
