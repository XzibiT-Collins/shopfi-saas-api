package com.example.perfume_budget.platform.dto;

import com.example.perfume_budget.platform.enums.TenantPlan;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TenantSignupRequest(
        @NotBlank @Size(min = 3, max = 40) String slug, // API-01 AC3: the real slug rule (TenantSlugs 3-40)
        @NotBlank @Size(max = 255) @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "must not contain control characters") String displayName,
        @NotBlank @Email @Size(max = 255) String ownerEmail,
        @NotBlank @Size(max = 255) @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "must not contain control characters") String ownerFullName,
        @NotBlank @Size(min = 8, max = 72) String password, // API-01 AC3: 8..72 (BCrypt); the 72-BYTE rule stays in the service
        TenantPlan plan) {

    /** Never prints the password. */
    @Override
    public String toString() {
        return "TenantSignupRequest[slug=" + slug + ", plan=" + plan + ", password=<redacted>]";
    }
}
