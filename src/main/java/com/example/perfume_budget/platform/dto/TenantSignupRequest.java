package com.example.perfume_budget.platform.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TenantSignupRequest(
        @NotBlank @Size(min = 3, max = 40) String slug, // API-01 AC3: the real slug rule (TenantSlugs 3-40)
        @NotBlank @Size(max = 255) @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "must not contain control characters") String displayName,
        @NotBlank @Email @Size(max = 255) String ownerEmail,
        @NotBlank @Size(max = 255) @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "must not contain control characters") String ownerFullName,
        @NotBlank @Size(min = 8, max = 72) String password) { // API-01 AC3: 8..72 (BCrypt); the 72-BYTE rule stays in the service
    // PLAN-01 (owner O1, plans.md section 15, Security requirement 3): there is NO plan, status or trial field. A client
    // `plan` (any value, any type) is an unknown property and ignored by Jackson; it never selects a plan and is never a 400.

    /** Never prints the password. */
    @Override
    public String toString() {
        return "TenantSignupRequest[slug=" + slug + ", password=<redacted>]";
    }
}
