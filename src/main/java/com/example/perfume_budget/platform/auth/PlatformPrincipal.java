package com.example.perfume_budget.platform.auth;

/** Authenticated platform super-admin (the Spring Security principal on {@code /api/v1/platform/**}). */
public record PlatformPrincipal(Long id, String email, String fullName) {
}
