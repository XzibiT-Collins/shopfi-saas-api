package com.example.perfume_budget.entitlement;

import com.example.perfume_budget.platform.enums.SubscriptionStatus;

import java.time.Instant;

/**
 * The cached part of a tenant's subscription ({@code plans:v1:tenant:{tenantId}}): plan id, stored status and trial end.
 * {@code readOnly} is deliberately NOT here: it is computed per request against the {@code Clock} (plans.md 15.1 item 7).
 */
public record TenantPlanState(long tenantId, long planId, SubscriptionStatus status, Instant trialEndsAt) {

    /** plans.md 13: CANCELED, or TRIALING with a trial end that is not after {@code now}. ACTIVE / PAST_DUE never. */
    public boolean readOnlyAt(Instant now) {
        return status == SubscriptionStatus.CANCELED
                || (status == SubscriptionStatus.TRIALING && trialEndsAt != null && !now.isBefore(trialEndsAt));
    }
}
