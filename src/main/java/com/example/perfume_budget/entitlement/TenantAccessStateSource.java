package com.example.perfume_budget.entitlement;

/**
 * Read side of the billing projection behind the entitlement cache (BILL-01c). BILL-01b implements it over the
 * {@code tenants.access_state} / {@code billing_managed} columns its migration adds; until then
 * {@link #PRE_BILLING} (FULL, not billing managed) is used, which keeps the pre-billing rule of plans.md 13 untouched.
 * Called only on a cache miss, with the tenant's immutable id.
 */
public interface TenantAccessStateSource {

    record Projection(AccessState accessState, boolean billingManaged) {
        public Projection {
            accessState = accessState == null ? AccessState.FULL : accessState;
        }
    }

    Projection PRE_BILLING_PROJECTION = new Projection(AccessState.FULL, false);

    TenantAccessStateSource PRE_BILLING = tenantId -> PRE_BILLING_PROJECTION;

    Projection load(long tenantId);
}
