package com.example.perfume_budget.theme;

/** One field-level validation error: JSON path into the submitted theme, stable code, English message. */
public record ThemeFieldError(String path, String code, String message) {
}
