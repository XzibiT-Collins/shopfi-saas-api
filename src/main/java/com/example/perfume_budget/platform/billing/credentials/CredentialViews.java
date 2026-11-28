package com.example.perfume_budget.platform.billing.credentials;

import com.example.perfume_budget.platform.billing.BillingProperties;
import com.example.perfume_budget.platform.billing.settings.BillingViews.CredentialStatusView;

/** The only place a credentials row becomes API output: configured, last 4, status and timestamps, never a key. */
public final class CredentialViews {

    private CredentialViews() {
    }

    public static CredentialStatusView of(BillingCredential c, BillingProperties props) {
        return new CredentialStatusView(c.getEnvironment(), c.isConfigured(), c.getStatus(), c.getPublicLast4(),
                c.getSecretLast4(), c.getVerifiedAt(), c.getFailureCode(), c.getLastWebhookAt(), c.getLastWebhookOkAt(),
                props.webhookUrl(c.getEnvironment()), c.getVersion(), c.getUpdatedAt(), c.getUpdatedByEmail());
    }
}
