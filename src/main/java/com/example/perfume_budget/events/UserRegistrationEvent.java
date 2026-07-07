package com.example.perfume_budget.events;

public record UserRegistrationEvent(
        String email,
        String otp
) {
}
