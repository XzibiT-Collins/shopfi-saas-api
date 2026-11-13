package com.example.perfume_budget.platform.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TenantVerifyRequest(
        @NotBlank @Size(max = 64) String slug,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 16) String otp) {

    @Override
    public String toString() {
        return "TenantVerifyRequest[slug=" + slug + ", otp=<redacted>]";
    }
}
