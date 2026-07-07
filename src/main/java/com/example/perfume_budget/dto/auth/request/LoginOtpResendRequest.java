package com.example.perfume_budget.dto.auth.request;

import jakarta.validation.constraints.NotBlank;

public record LoginOtpResendRequest(
        @NotBlank(message = "Login challenge token is required")
        String challengeToken
) {
}
