package com.example.perfume_budget.dto.auth.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record LoginRequest(
        @NotBlank @NotNull String email,
        @NotBlank @NotNull String password
) {
}
