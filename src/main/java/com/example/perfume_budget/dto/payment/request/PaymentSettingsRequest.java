package com.example.perfume_budget.dto.payment.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Tenant admin submits its own Paystack credentials. {@code secretKey} may be blank on update
 * to leave the existing secret unchanged;
 * the service rejects a blank secret only when none is stored yet (first-time setup).
 */
public record PaymentSettingsRequest(String secretKey, @NotBlank(message = "Public Key is required")String publicKey, boolean enabled) {
}
