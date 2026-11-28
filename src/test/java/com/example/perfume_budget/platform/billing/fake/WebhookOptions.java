package com.example.perfume_budget.platform.billing.fake;

/**
 * How the fake emits one webhook: {@code copies} (duplicates), {@code delayMillis}, {@code signature} (VALID real
 * HMAC-SHA512 of the exact body with the fake secret, BAD = a valid-looking signature of other bytes, MISSING = no header)
 * and {@code drop} (the event is recorded but never sent).
 */
public record WebhookOptions(int copies, long delayMillis, Signature signature, boolean drop) {

    public enum Signature { VALID, BAD, MISSING }

    public static WebhookOptions once() {
        return new WebhookOptions(1, 0, Signature.VALID, false);
    }

    public WebhookOptions copies(int n) {
        return new WebhookOptions(n, delayMillis, signature, drop);
    }

    public WebhookOptions delay(long millis) {
        return new WebhookOptions(copies, millis, signature, drop);
    }

    public WebhookOptions signature(Signature s) {
        return new WebhookOptions(copies, delayMillis, s, drop);
    }

    public WebhookOptions dropped() {
        return new WebhookOptions(copies, delayMillis, signature, true);
    }
}
