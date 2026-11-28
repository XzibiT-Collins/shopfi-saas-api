package com.example.perfume_budget.platform.billing.fake;

/**
 * Standalone fake Paystack for the frontend real-stack e2e and local development (BILL-01 AC7). Test sources only: run it from
 * the test classpath with {@code scripts/fake-paystack.sh [port]}. Point the application at it with
 * {@code BILLING_PAYSTACK_TEST_BASE_URL=http://127.0.0.1:<port>} and
 * {@code BILLING_PAYSTACK_CHECKOUT_URL_PREFIX=http://127.0.0.1:<port>/checkout/} under the {@code dev} profile (the
 * application refuses these values under any other profile). Optional arguments: {@code --secret sk_test_...},
 * {@code --webhook-url URL --webhook-secret S}. The control API is under {@code /__control/} (see {@link FakePaystackServer}).
 */
public final class FakePaystackMain {

    private FakePaystackMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        int port = 19090;
        String secret = null;
        String webhookUrl = null;
        String webhookSecret = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--secret" -> secret = args[++i];
                case "--webhook-url" -> webhookUrl = args[++i];
                case "--webhook-secret" -> webhookSecret = args[++i];
                default -> port = Integer.parseInt(args[i]);
            }
        }
        FakePaystackServer fake = FakePaystackServer.start(port);
        if (secret != null) {
            fake.acceptSecret(secret);
        }
        if (webhookUrl != null && webhookSecret != null) {
            fake.webhookTarget(webhookUrl, webhookSecret);
        }
        System.out.println("Fake Paystack listening on " + fake.baseUrl() + " (checkout prefix " + fake.checkoutPrefix() + ")");
        Thread.currentThread().join();
    }
}
