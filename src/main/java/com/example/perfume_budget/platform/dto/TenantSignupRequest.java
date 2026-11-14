package com.example.perfume_budget.platform.dto;

import com.example.perfume_budget.platform.enums.TenantPlan;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TenantSignupRequest(
        @NotBlank @Size(max = 64) String slug,
        @NotBlank @Size(max = 255) @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "must not contain control characters") String displayName,
        @NotBlank @Email @Size(max = 255) String ownerEmail,
        @NotBlank @Size(max = 255) @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "must not contain control characters") String ownerFullName,
        @NotBlank @Size(max = 200) String password,
        TenantPlan plan) {

    /** Never prints the password. */
    @Override
    public String toString() {
        return "TenantSignupRequest[slug=" + slug + ", plan=" + plan + ", password=<redacted>]";
    }
}
