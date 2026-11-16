package com.example.perfume_budget.dto.payment.response;

/**
 * Payment settings view for the tenant admin. The secret is never returned, only whether one is configured.
 *
 * <p>API-06, webhook URL shown once: {@code webhookUrl} (the URL to paste into the tenant's Paystack dashboard, which
 * embeds the routing token) is returned ONLY by the call that minted the token: {@code POST /rotate-webhook-token},
 * or the {@code PUT} that saved the settings for the first time. Every other response, including every {@code GET},
 * has {@code webhookUrl = null}, {@code hasWebhookToken} (whether a URL exists) and {@code webhookUrlMasked} (the URL
 * with all but the last 4 token characters replaced, so the admin can recognise which URL is live). A lost URL is
 * recovered by rotating, which kills the old one.
 */
public record PaymentSettingsResponse(boolean secretConfigured, String publicKey,
                                      boolean enabled, String webhookUrl,
                                      boolean hasWebhookToken, String webhookUrlMasked) {

    /** Never prints the full webhook URL (it carries the routing token), so a stray log line cannot leak it. */
    @Override
    public String toString() {
        return "PaymentSettingsResponse[secretConfigured=" + secretConfigured + ", publicKey=" + publicKey
                + ", enabled=" + enabled + ", webhookUrl=" + (webhookUrl == null ? "<none>" : "<redacted>")
                + ", hasWebhookToken=" + hasWebhookToken + ", webhookUrlMasked=" + webhookUrlMasked + "]";
    }
}
