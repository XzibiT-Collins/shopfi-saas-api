package com.example.perfume_budget.platform.billing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Server configuration of the platform billing (never request input): the Paystack base URL per environment, the allowed
 * hosted-checkout prefix, the public webhook / callback base URLs and the platform-wide timeouts. The defaults are the
 * production values; {@link BillingStartupGuard} refuses any other base URL or checkout prefix outside the dev and test
 * profiles (the fake Paystack is reached by pointing these at it).
 */
@Component
public class BillingProperties {

    public static final String PRODUCTION_PAYSTACK_URL = "https://api.paystack.co";
    public static final String PRODUCTION_CHECKOUT_PREFIX = "https://checkout.paystack.com/";

    private final String testBaseUrl;
    private final String liveBaseUrl;
    private final String checkoutUrlPrefix;
    private final String webhookBaseUrl;
    private final String callbackBaseUrl;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final int maxConcurrentCalls;

    public BillingProperties(
            @Value("${billing.paystack.test-base-url:" + PRODUCTION_PAYSTACK_URL + "}") String testBaseUrl,
            @Value("${billing.paystack.live-base-url:" + PRODUCTION_PAYSTACK_URL + "}") String liveBaseUrl,
            @Value("${billing.paystack.checkout-url-prefix:" + PRODUCTION_CHECKOUT_PREFIX + "}") String checkoutUrlPrefix,
            @Value("${billing.webhook-base-url:${platform.api-base-url:http://localhost:8080}}") String webhookBaseUrl,
            @Value("${billing.callback-base-url:${billing.webhook-base-url:${platform.api-base-url:http://localhost:8080}}}") String callbackBaseUrl,
            @Value("${billing.paystack.connect-timeout-ms:3000}") int connectTimeoutMillis,
            @Value("${billing.paystack.read-timeout-ms:15000}") int readTimeoutMillis,
            @Value("${billing.paystack.max-concurrent-calls:8}") int maxConcurrentCalls) {
        this.testBaseUrl = trimSlash(testBaseUrl);
        this.liveBaseUrl = trimSlash(liveBaseUrl);
        this.checkoutUrlPrefix = checkoutUrlPrefix;
        this.webhookBaseUrl = trimSlash(webhookBaseUrl);
        this.callbackBaseUrl = trimSlash(callbackBaseUrl);
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.maxConcurrentCalls = Math.max(1, maxConcurrentCalls);
    }

    private static String trimSlash(String url) {
        String u = url == null ? "" : url.trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    public String baseUrl(BillingEnvironment environment) {
        return environment == BillingEnvironment.TEST ? testBaseUrl : liveBaseUrl;
    }

    public String checkoutUrlPrefix() {
        return checkoutUrlPrefix;
    }

    /** The URL to paste in that environment's Paystack dashboard; built from configuration, never from input. */
    public String webhookUrl(BillingEnvironment environment) {
        return webhookBaseUrl + "/api/v1/platform/billing/webhooks/paystack/" + environment.pathValue();
    }

    public String callbackBaseUrl() {
        return callbackBaseUrl;
    }

    public int connectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public int readTimeoutMillis() {
        return readTimeoutMillis;
    }

    public int maxConcurrentCalls() {
        return maxConcurrentCalls;
    }
}
