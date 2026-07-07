package com.example.perfume_budget.dto.auth.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RegistrationRequest(
        @NotBlank @NotNull String email,
//        @NotBlank @NotNull String username,
        @NotBlank @NotNull String fullName,
        @NotBlank @NotNull String password,
        @NotBlank @NotNull String confirmPassword
) {
}
