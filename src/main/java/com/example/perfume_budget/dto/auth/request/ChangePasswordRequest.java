package com.example.perfume_budget.dto.auth.request;

import com.example.perfume_budget.validation.ValidPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * API-RD-08: {@code POST /auth/change-password}. {@code newPassword} follows the policy of signup and reset
 * ({@link com.example.perfume_budget.validation.PasswordPolicy}); {@code confirmPassword} is optional and, when sent,
 * must equal it. {@code currentPassword} is capped (BCrypt reads 72 bytes) so oversized input is never hashed.
 */
public record ChangePasswordRequest(
        @NotBlank(message = "Current password is required")
        @Size(max = 128, message = "Current password is too long")
        String currentPassword,
        @NotBlank(message = "New password is required")
        @ValidPassword
        String newPassword,
        String confirmPassword
) {
    @Override
    public String toString() {
        return "ChangePasswordRequest[currentPassword=***, newPassword=***, confirmPassword=***]";
    }
}
