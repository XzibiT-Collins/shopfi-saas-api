package com.example.perfume_budget.dto.payment.response;

/**
 * Payment settings view for the tenant admin. The secret is never returned — only whether one
 * is configured. {@code webhookUrl} is the URL to paste into the tenant's Paystack dashboard.
 */
public record PaymentSettingsResponse(boolean secretConfigured, String publicKey,
                                      boolean enabled, String webhookUrl) {
}
