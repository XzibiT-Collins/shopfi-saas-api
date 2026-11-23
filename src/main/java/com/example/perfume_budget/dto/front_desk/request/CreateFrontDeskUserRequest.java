package com.example.perfume_budget.dto.front_desk.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A raw length under 8 stays a bean-validation 400 (Validation failed + data.password); the rest of PasswordPolicy is checked in the service with a data.code (API-RD-11b). */
public record CreateFrontDeskUserRequest(
        @NotBlank String fullName,
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, message = "Password must be at least 8 characters long") String password,
        @NotNull Boolean isActive
) {
}
