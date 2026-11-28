package com.example.perfume_budget.platform.billing;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BILL-01 AC5 and billing.md 19.1 / 21 items 2 and 17: outside the dev and test profiles the application refuses to start
 * when an encryption key is the dev default or shorter than 32 characters, when a Paystack base URL is not exactly
 * {@code https://api.paystack.co} or the checkout prefix is not {@code https://checkout.paystack.com/}.
 */
class BillingStartupGuardTest {

    private static final String GOOD_KEY = "a-production-key-of-at-least-32-chars-x";

    private static MockEnvironment env(String profile, String platformKey) {
        MockEnvironment e = new MockEnvironment().withProperty("platform.encryption.key", platformKey);
        if (profile != null) {
            e.setActiveProfiles(profile);
        }
        return e;
    }

    private static BillingProperties props(MockEnvironment e) {
        return new BillingProperties(e.getProperty("billing.paystack.test-base-url", BillingProperties.PRODUCTION_PAYSTACK_URL),
                e.getProperty("billing.paystack.live-base-url", BillingProperties.PRODUCTION_PAYSTACK_URL),
                e.getProperty("billing.paystack.checkout-url-prefix", BillingProperties.PRODUCTION_CHECKOUT_PREFIX),
                "https://api.shopfi.test", "https://api.shopfi.test", 1000, 1000, 2);
    }

    private static void start(MockEnvironment e) {
        new BillingStartupGuard(e, props(e));
    }

    @Test
    void productionWithGoodKeysAndOfficialUrlsStarts() {
        start(env("prod", GOOD_KEY));
        MockEnvironment withBilling = env("prod", GOOD_KEY).withProperty("billing.encryption.key", GOOD_KEY + "2");
        start(withBilling);
    }

    @Test
    void devDefaultKeyIsRefusedOutsideDev_inProdAndWithNoProfileAtAll() {
        for (String profile : new String[]{"prod", "staging", null}) {
            assertThatThrownBy(() -> start(env(profile, BillingStartupGuard.DEV_DEFAULT_KEY)))
                    .as("profile " + profile).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("platform.encryption.key").hasMessageContaining("development default");
        }
    }

    @Test
    void theDevDefaultAsTheBillingKeyIsRefusedToo() {
        MockEnvironment e = env("prod", GOOD_KEY).withProperty("billing.encryption.key", BillingStartupGuard.DEV_DEFAULT_KEY);
        assertThatThrownBy(() -> start(e)).hasMessageContaining("billing.encryption.key").hasMessageContaining("development default");
    }

    @Test
    void shortKeysAreRefused() {
        assertThatThrownBy(() -> start(env("prod", "x".repeat(31)))).hasMessageContaining("at least 32");
        start(env("prod", "x".repeat(32)));
        MockEnvironment e = env("prod", GOOD_KEY).withProperty("billing.encryption.key", "short-billing-key");
        assertThatThrownBy(() -> start(e)).hasMessageContaining("billing.encryption.key").hasMessageContaining("at least 32");
    }

    @Test
    void aMissingPlatformKeyIsRefusedOutsideDev() {
        assertThatThrownBy(() -> start(env("prod", ""))).hasMessageContaining("platform.encryption.key must be set");
    }

    @Test
    void devAndTestProfilesAcceptTheDevDefaultAndShortKeys() {
        start(env("dev", BillingStartupGuard.DEV_DEFAULT_KEY));
        start(env("test", "test-encryption-key"));
    }

    @Test
    void theMessageNeverContainsAKey() {
        String secretish = "super-secret-short";
        assertThatThrownBy(() -> start(env("prod", secretish))).hasMessageNotContaining(secretish);
    }

    @Test
    void anyPaystackBaseUrlOtherThanTheOfficialOneIsRefusedOutsideDevAndTest() {
        for (String url : new String[]{"http://localhost:8089", "https://api.paystack.co/", "http://api.paystack.co",
                "https://api.paystack.co.evil.example", "https://evil.example", "https://api.paystack.com", "https://API.PAYSTACK.CO"}) {
            for (String envKey : new String[]{"test", "live"}) {
                MockEnvironment e = env("prod", GOOD_KEY).withProperty("billing.paystack." + envKey + "-base-url", url);
                // BillingProperties trims one trailing slash, so the slash variant normalises to the official URL (accepted)
                List<String> problems = BillingStartupGuard.problems(false, GOOD_KEY, "", props(e));
                if (url.equals("https://api.paystack.co/")) {
                    assertThat(problems).as(url).isEmpty();
                } else {
                    assertThat(problems).as(envKey + " " + url).hasSize(1);
                    assertThatThrownBy(() -> start(e)).hasMessageContaining("billing.paystack." + envKey + "-base-url");
                }
            }
        }
    }

    @Test
    void devAndTestMayPointAtTheFake() {
        MockEnvironment e = env("dev", GOOD_KEY).withProperty("billing.paystack.test-base-url", "http://127.0.0.1:19090")
                .withProperty("billing.paystack.checkout-url-prefix", "http://127.0.0.1:19090/checkout/");
        start(e);
        MockEnvironment t = env("test", GOOD_KEY).withProperty("billing.paystack.live-base-url", "http://127.0.0.1:19090");
        start(t);
    }

    @Test
    void theCheckoutPrefixMustBeThePaystackHostOutsideDevAndTest() {
        for (String prefix : new String[]{"http://127.0.0.1:19090/checkout/", "https://checkout.paystack.com", "https://checkout.paystack.com.evil.example/"}) {
            MockEnvironment e = env("prod", GOOD_KEY).withProperty("billing.paystack.checkout-url-prefix", prefix);
            assertThatThrownBy(() -> start(e)).as(prefix).hasMessageContaining("checkout-url-prefix");
        }
    }

    @Test
    void theProductionDefaultsOfTheProperties() {
        BillingProperties p = new BillingProperties(BillingProperties.PRODUCTION_PAYSTACK_URL, BillingProperties.PRODUCTION_PAYSTACK_URL,
                BillingProperties.PRODUCTION_CHECKOUT_PREFIX, "https://api.shopfi.test/", "https://api.shopfi.test", 1, 1, 1);
        assertThat(p.baseUrl(BillingEnvironment.TEST)).isEqualTo("https://api.paystack.co");
        assertThat(p.webhookUrl(BillingEnvironment.LIVE)).isEqualTo("https://api.shopfi.test/api/v1/platform/billing/webhooks/paystack/live");
        assertThat(BillingStartupGuard.problems(false, GOOD_KEY, "", p)).isEmpty();
    }
}
