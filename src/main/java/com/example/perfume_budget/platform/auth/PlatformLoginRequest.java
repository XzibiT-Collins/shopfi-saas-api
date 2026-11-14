package com.example.perfume_budget.platform.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PlatformLoginRequest(
        @NotBlank @Size(max = 254) String email,
        // BCrypt only uses the first 72 bytes; bound the input.
        @NotBlank @Size(max = 72) String password) {

    /** BCrypt truncates at 72 BYTES: reject (400, like the character cap) anything longer, multibyte included. */
    @jakarta.validation.constraints.AssertTrue(message = "size must be between 0 and 72")
    public boolean isPasswordWithinBcryptByteLimit() {
        return password == null || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;
    }

    public static final int MAX_PASSWORD_BYTES = 72;

    @Override
    public String toString() {
        return "PlatformLoginRequest[email=" + email + ", password=***]";
    }
}
