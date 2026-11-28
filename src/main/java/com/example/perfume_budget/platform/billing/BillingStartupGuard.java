package com.example.perfume_budget.platform.billing;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Startup refusals of the platform billing (BILL-01 AC5, billing.md 19.1, 21 items 2 and 17). Outside the {@code dev} and
 * {@code test} profiles (an UNSET profile counts as production) the application REFUSES TO START when
 * <ul>
 *   <li>{@code platform.encryption.key} or {@code billing.encryption.key} is the dev default or shorter than 32 chars;</li>
 *   <li>either environment's Paystack base URL is not exactly {@code https://api.paystack.co} (this is what keeps the
 *       fake Paystack, or any look-alike host, away from real keys);</li>
 *   <li>the hosted-checkout prefix is not exactly {@code https://checkout.paystack.com/}.</li>
 * </ul>
 * The message names the property and the rule, never a value (a key must not reach a log).
 */
@Component
public class BillingStartupGuard {

    public static final String DEV_DEFAULT_KEY = "dev-insecure-change-me";
    public static final int MIN_KEY_LENGTH = 32;

    public BillingStartupGuard(Environment env, BillingProperties props) {
        boolean lenient = env.acceptsProfiles(Profiles.of("dev", "test"));
        List<String> problems = problems(lenient, env.getProperty("platform.encryption.key", ""),
                env.getProperty("billing.encryption.key", ""), props);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start: " + String.join("; ", problems));
        }
    }

    /** Pure rule set, unit-tested directly. */
    public static List<String> problems(boolean lenientProfile, String platformKey, String billingKey, BillingProperties props) {
        List<String> out = new ArrayList<>();
        if (lenientProfile) {
            return out;
        }
        keyProblem("platform.encryption.key", platformKey, true, out);
        keyProblem("billing.encryption.key", billingKey, false, out);
        for (BillingEnvironment e : BillingEnvironment.values()) {
            if (!BillingProperties.PRODUCTION_PAYSTACK_URL.equals(props.baseUrl(e))) {
                out.add("billing.paystack." + e.pathValue() + "-base-url must be exactly " + BillingProperties.PRODUCTION_PAYSTACK_URL
                        + " outside the dev and test profiles");
            }
        }
        if (!BillingProperties.PRODUCTION_CHECKOUT_PREFIX.equals(props.checkoutUrlPrefix())) {
            out.add("billing.paystack.checkout-url-prefix must be exactly " + BillingProperties.PRODUCTION_CHECKOUT_PREFIX
                    + " outside the dev and test profiles");
        }
        return out;
    }

    private static void keyProblem(String property, String key, boolean required, List<String> out) {
        if (key == null || key.isBlank()) {
            if (required) {
                out.add(property + " must be set");
            }
            return;
        }
        if (DEV_DEFAULT_KEY.equals(key)) {
            out.add(property + " must not be the development default key");
        } else if (key.length() < MIN_KEY_LENGTH) {
            out.add(property + " must be at least " + MIN_KEY_LENGTH + " characters");
        }
    }
}
