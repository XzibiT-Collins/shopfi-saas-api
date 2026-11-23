package com.example.perfume_budget.platform.auth;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * API-RD-07 AC1. {@code newPassword} is checked against {@link com.example.perfume_budget.validation.PasswordPolicy}
 * (the policy of signup and reset) in the service so the violation text is the policy's own. Both fields are capped
 * (BCrypt reads 72 bytes only) before anything is hashed.
 */
public record PlatformChangePasswordRequest(
        @NotBlank @Size(max = 72) String currentPassword,
        @NotBlank @Size(max = 72) String newPassword) {

    @AssertTrue(message = "size must be between 0 and 72")
    public boolean isWithinBcryptByteLimit() {
        return within(currentPassword) && within(newPassword);
    }

    private static boolean within(String s) {
        return s == null || s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72;
    }

    @Override
    public String toString() {
        return "PlatformChangePasswordRequest[currentPassword=***, newPassword=***]";
    }
}
