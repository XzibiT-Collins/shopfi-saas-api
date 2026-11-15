package com.example.perfume_budget.dto.payment.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Tenant admin submits its own Paystack credentials. {@code secretKey} may be blank on update
 * to leave the existing secret unchanged;
 * the service rejects a blank secret only when none is stored yet (first-time setup).
 *
 * <p>API-04 format checks (shape only, no call to Paystack): the secret is {@code sk_test_...} / {@code sk_live_...},
 * the public key {@code pk_test_...} / {@code pk_live_...}, and when both are sent they must be the same mode. The
 * validation messages are fixed strings: they never echo the submitted value. {@link #toString()} redacts the secret.
 */
public record PaymentSettingsRequest(
        @Pattern(regexp = "^\\s*$|^\\s*sk_(test|live)_[A-Za-z0-9_]{4,200}\\s*$",
                message = "Secret Key must look like sk_test_... or sk_live_...")
        String secretKey,
        @NotBlank(message = "Public Key is required")
        @Pattern(regexp = "^\\s*$|^\\s*pk_(test|live)_[A-Za-z0-9_]{4,200}\\s*$",
                message = "Public Key must look like pk_test_... or pk_live_...")
        String publicKey,
        boolean enabled) {

    @com.fasterxml.jackson.annotation.JsonIgnore
    @AssertTrue(message = "Secret Key and Public Key must both be test keys or both be live keys")
    public boolean isKeyModesConsistent() {
        if (secretKey == null || secretKey.isBlank() || publicKey == null || publicKey.isBlank()) {
            return true;
        }
        return secretKey.strip().startsWith("sk_live_") == publicKey.strip().startsWith("pk_live_");
    }

    @Override
    public String toString() {
        return "PaymentSettingsRequest[secretKey=" + (secretKey == null || secretKey.isBlank() ? "<none>" : "<redacted>")
                + ", publicKey=<redacted>, enabled=" + enabled + "]";
    }
}
