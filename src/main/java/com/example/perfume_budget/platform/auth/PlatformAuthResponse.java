package com.example.perfume_budget.platform.auth;

/** Session info only. Tokens travel exclusively in HttpOnly cookies, never in the body. */
public record PlatformAuthResponse(String email, String fullName, String role) {
}
