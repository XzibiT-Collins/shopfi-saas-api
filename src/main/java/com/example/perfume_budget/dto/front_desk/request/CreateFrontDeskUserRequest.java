package com.example.perfume_budget.dto.front_desk.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** The password is checked in the service against PasswordPolicy (API-RD-11b), so every refusal carries a data.code. */
public record CreateFrontDeskUserRequest(
        @NotBlank String fullName,
        @NotBlank @Email String email,
        @NotBlank String password,
        @NotNull Boolean isActive
) {
}
