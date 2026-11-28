package com.example.perfume_budget.platform.billing;

import com.example.perfume_budget.exception.ApiCodeException;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.exception.RetryAfterApiCodeException;
import org.springframework.http.HttpStatus;

import java.util.Map;

/** The coded billing errors of docs/api/billing.md 17.3 and errors.md (exact codes and texts). */
public final class BillingErrors {

    public static final String BILLING_SETTINGS_VERSION_CONFLICT = "BILLING_SETTINGS_VERSION_CONFLICT";
    public static final String CREDENTIALS_VERSION_CONFLICT = "CREDENTIALS_VERSION_CONFLICT";
    public static final String CREDENTIALS_NOT_CONFIGURED = "CREDENTIALS_NOT_CONFIGURED";
    public static final String PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE";

    private BillingErrors() {
    }

    /** 409, nothing written, never the generic 503 TRY_AGAIN. */
    public static ApiCodeException settingsVersionConflict(long expected, long current) {
        return new ApiCodeException(HttpStatus.CONFLICT, BILLING_SETTINGS_VERSION_CONFLICT,
                "Billing settings were changed by someone else. Reload and review.",
                Map.of("expectedVersion", expected, "currentVersion", current));
    }

    public static ApiCodeException credentialsVersionConflict(long expected, long current) {
        return new ApiCodeException(HttpStatus.CONFLICT, CREDENTIALS_VERSION_CONFLICT,
                "The credentials were changed by someone else. Reload and review.",
                Map.of("expectedVersion", expected, "currentVersion", current));
    }

    public static ApiCodeException credentialsNotConfigured() {
        return new ApiCodeException(HttpStatus.CONFLICT, CREDENTIALS_NOT_CONFIGURED, "No keys are stored for this environment.");
    }

    /** 503 with Retry-After: Paystack unreachable, circuit open, or a checkout URL off the allowed host. */
    public static ApiCodeException providerUnavailable() {
        return new RetryAfterApiCodeException(HttpStatus.SERVICE_UNAVAILABLE, PROVIDER_UNAVAILABLE,
                "The payment provider is not reachable right now. Try again shortly.", 5);
    }

    /** An environment that is neither {@code test} nor {@code live}. */
    public static ResourceNotFoundException unknownEnvironment() {
        return new ResourceNotFoundException("Not found");
    }
}
