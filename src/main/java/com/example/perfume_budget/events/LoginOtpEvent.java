package com.example.perfume_budget.events;

public record LoginOtpEvent(
        String email,
        String otp
) {
}
