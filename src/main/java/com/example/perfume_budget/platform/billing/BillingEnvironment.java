package com.example.perfume_budget.platform.billing;

import java.util.Optional;

/** The two Paystack environments of the platform's own billing account (billing.md section 1). */
public enum BillingEnvironment {
    TEST, LIVE;

    /** Lower case form used in URL paths ({@code test}, {@code live}). */
    public String pathValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public String publicKeyPrefix() {
        return "pk_" + pathValue() + "_";
    }

    public String secretKeyPrefix() {
        return "sk_" + pathValue() + "_";
    }

    /** Exactly {@code test} or {@code live} (lower case); anything else is absent so the route answers 404. */
    public static Optional<BillingEnvironment> fromPath(String path) {
        if ("test".equals(path)) {
            return Optional.of(TEST);
        }
        if ("live".equals(path)) {
            return Optional.of(LIVE);
        }
        return Optional.empty();
    }
}
