package com.example.perfume_budget.dto.auth.request;

import jakarta.validation.constraints.NotBlank;

public record LoginOtpVerificationRequest(
        @NotBlank(message = "Login challenge token is required")
        String challengeToken,
        @NotBlank(message = "OTP is required")
        String otp
) {
}
