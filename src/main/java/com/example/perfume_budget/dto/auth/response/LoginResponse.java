package com.example.perfume_budget.dto.auth.response;

import lombok.Builder;

@Builder
public record LoginResponse(
        boolean requiresOtp,
        String challengeToken,
        String email,
        AuthResponse auth
) {
}
