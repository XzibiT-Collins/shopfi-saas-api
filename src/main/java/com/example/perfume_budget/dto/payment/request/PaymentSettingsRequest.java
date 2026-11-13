package com.example.perfume_budget.dto.payment.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Tenant admin submits its own Paystack credentials. {@code secretKey} may be blank on update
 * to leave the existing secret unchanged.
 */
public record PaymentSettingsRequest(@NotBlank(message = "Secret Key is required") String secretKey, @NotBlank(message = "Public Key is required")String publicKey, boolean enabled) {
}
