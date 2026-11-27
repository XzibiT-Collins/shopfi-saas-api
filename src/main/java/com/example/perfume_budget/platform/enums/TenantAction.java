package com.example.perfume_budget.platform.enums;

import java.util.List;

/** Platform-admin actions on a tenant (PLAT-02). Stored by name in the audit log. */
public enum TenantAction {
    APPROVE,
    SUSPEND,
    REACTIVATE,
    REPROVISION,
    /**
     * PLAN-01: the tenant's plan, trial end or subscription status changed ({@code PUT /platform/tenants/{slug}/plan}).
     * Not a lifecycle action: the registry status does not change and {@link com.example.perfume_budget.platform.service.TenantStateMachine}
     * never sees it; the audit row has previous status = new status.
     */
    CHANGE_PLAN;

    /** The lifecycle actions of the tenant state machine (everything except {@link #CHANGE_PLAN}). */
    public static final List<TenantAction> LIFECYCLE = List.of(APPROVE, SUSPEND, REACTIVATE, REPROVISION);
}
