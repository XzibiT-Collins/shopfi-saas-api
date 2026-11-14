package com.example.perfume_budget.events;

public record UserRegistrationEvent(
        String email,
        String otp
) {

    /** The OTP is a credential: never print it (events can end up in logs, debuggers and exception messages). */
    @Override
    public String toString() {
        return "UserRegistrationEvent[email=" + email + ", otp=<redacted>]";
    }
}
