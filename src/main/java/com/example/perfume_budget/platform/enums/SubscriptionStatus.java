package com.example.perfume_budget.platform.enums;

/**
 * A tenant's subscription state (PLAN-01, docs/api/plans.md). {@code TRIALING} comes from sign-up or a trial extension,
 * {@code ACTIVE} / {@code CANCELED} from the platform console (manual bridge until PLAN-06 billing), {@code PAST_DUE} from
 * PLAN-06. Stored by name in {@code master.tenants.subscription_status}.
 */
public enum SubscriptionStatus {
    TRIALING,
    ACTIVE,
    PAST_DUE,
    CANCELED
}
