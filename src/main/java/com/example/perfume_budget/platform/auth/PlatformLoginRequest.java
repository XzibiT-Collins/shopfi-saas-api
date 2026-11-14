package com.example.perfume_budget.platform.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PlatformLoginRequest(
        @NotBlank @Size(max = 254) String email,
        // BCrypt only uses the first 72 bytes; bound the input.
        @NotBlank @Size(max = 72) String password) {

    @Override
    public String toString() {
        return "PlatformLoginRequest[email=" + email + ", password=***]";
    }
}
