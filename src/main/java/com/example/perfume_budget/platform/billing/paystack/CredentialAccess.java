package com.example.perfume_budget.platform.billing.paystack;

import com.example.perfume_budget.platform.billing.BillingEnvironment;

import java.util.Optional;

/**
 * What the Paystack client needs from the stored credentials, resolved at CALL time (a key rotation is effective at once).
 * The client never sees a shop's gateway configuration: this is the platform's own billing account only.
 */
public interface CredentialAccess {

    /** The decrypted secret key of the environment, empty when none is stored. The caller must never log it. */
    Optional<String> secretKey(BillingEnvironment environment);

    /** Paystack answered 401 / 403 to a billing call: a platform fault (billing.md 4.4). Marks the environment FAILED. */
    void keyRejected(BillingEnvironment environment);
}
