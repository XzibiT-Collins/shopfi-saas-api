package com.example.perfume_budget.platform.billing;

import java.time.Instant;

/**
 * One synchronous billing round (billing.md 19.2 {@code POST /api/v1/dev/billing/run}). The scheduled jobs of BILL-01b..04
 * provide the real implementation; {@link IdleBillingRound} (this ticket) does nothing and reports zeros.
 */
public interface BillingRound {

    record Result(Instant now, int trialCharges, int renewals, int retries, int graceExpiries, int reminders, int reconciled) {
    }

    Result runOnce(Instant now);
}
