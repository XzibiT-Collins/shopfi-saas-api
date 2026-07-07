package com.example.perfume_budget.events;

public record ResetPasswordEvent(
        String email,
        String fullName,
        String token
) {
}
