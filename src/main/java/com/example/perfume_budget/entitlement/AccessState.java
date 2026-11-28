package com.example.perfume_budget.entitlement;

/**
 * Billing access state of a tenant (billing.md 3, BILL-01b projection column {@code tenants.access_state}): FULL, GRACE
 * (payment overdue, everything still works) or READ_ONLY (paid features read-only exactly as plans.md 13).
 */
public enum AccessState {
    FULL, GRACE, READ_ONLY
}
