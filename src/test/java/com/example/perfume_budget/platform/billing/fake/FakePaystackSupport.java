package com.example.perfume_budget.platform.billing.fake;

/**
 * Points every Spring context of the test JVM at the one shared fake Paystack: sets the billing properties as JVM system
 * properties (which every Environment reads), BEFORE any context boots, so all contexts share the same values and the
 * Spring context cache is not split. Called from {@code AbstractPostgresIntegrationTest}'s static initialiser.
 */
public final class FakePaystackSupport {

    private FakePaystackSupport() {
    }

    public static void install() {
        FakePaystackServer fake = FakePaystackServer.shared();
        System.setProperty("billing.paystack.test-base-url", fake.baseUrl());
        System.setProperty("billing.paystack.live-base-url", fake.baseUrl());
        System.setProperty("billing.paystack.checkout-url-prefix", fake.checkoutPrefix());
        System.setProperty("billing.webhook-base-url", "https://api.shopfi.test");
        System.setProperty("billing.callback-base-url", "https://api.shopfi.test");
    }
}
