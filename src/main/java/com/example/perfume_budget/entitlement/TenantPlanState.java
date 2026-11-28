package com.example.perfume_budget.entitlement;

import com.example.perfume_budget.platform.enums.SubscriptionStatus;

import java.time.Instant;

/**
 * The cached part of a tenant's subscription ({@code plans:v1:tenant:{tenantId}}): plan id, stored status, trial end and
 * (BILL-01c) the billing projection {@code accessState} / {@code billingManaged}. {@code readOnly} is deliberately NOT
 * here: it is computed per request against the {@code Clock} (plans.md 15.1 item 7).
 */
public record TenantPlanState(long tenantId, long planId, SubscriptionStatus status, Instant trialEndsAt,
                              AccessState accessState, boolean billingManaged) {

    public TenantPlanState {
        accessState = accessState == null ? AccessState.FULL : accessState; // an entry cached before BILL-01c
    }

    /** A tenant that billing does not manage (FULL access): the pre-billing rule alone decides. */
    public TenantPlanState(long tenantId, long planId, SubscriptionStatus status, Instant trialEndsAt) {
        this(tenantId, planId, status, trialEndsAt, AccessState.FULL, false);
    }

    /**
     * Read-only when billing says so ({@code accessState == READ_ONLY}), OR when billing does not manage the tenant and the
     * pre-billing rule of plans.md 13 holds: CANCELED, or TRIALING with a trial end that is not after {@code now}.
     * ACTIVE / PAST_DUE are never read-only on their own. For a billing-managed tenant only the access state decides: with
     * billing the end of a trial means a charge, not a restriction (billing.md 3).
     */
    public boolean readOnlyAt(Instant now) {
        if (accessState == AccessState.READ_ONLY) {
            return true;
        }
        if (billingManaged) {
            return false;
        }
        return status == SubscriptionStatus.CANCELED
                || (status == SubscriptionStatus.TRIALING && trialEndsAt != null && !now.isBefore(trialEndsAt));
    }
}
