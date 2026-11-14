package com.example.perfume_budget.dto.auth.request;

import com.example.perfume_budget.validation.ValidPassword;
import jakarta.validation.constraints.NotBlank;

public record ResetPasswordRequest(
        @NotBlank(message = "Password is required")
        @ValidPassword
        String password,
        @NotBlank(message = "Confirm password is required")
        @ValidPassword
        String confirmPassword
) {
}
