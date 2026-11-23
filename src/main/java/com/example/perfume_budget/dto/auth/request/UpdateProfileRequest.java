package com.example.perfume_budget.dto.auth.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * API-RD-08: {@code PATCH /auth/me}. Only {@code fullName} is writable here; the e-mail, role, activity and
 * password have their own flows. The value is stripped and must not contain control characters (checked in the service).
 */
public record UpdateProfileRequest(
        @NotBlank(message = "Full name is required")
        @Size(max = 100, message = "Full name must be at most 100 characters")
        String fullName
) {
}
