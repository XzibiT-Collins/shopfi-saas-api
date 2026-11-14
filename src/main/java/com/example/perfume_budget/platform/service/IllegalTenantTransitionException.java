package com.example.perfume_budget.platform.service;

import com.example.perfume_budget.platform.enums.TenantAction;
import com.example.perfume_budget.platform.enums.TenantStatus;

/** The requested action is not legal from the tenant's current status (HTTP 409). */
public class IllegalTenantTransitionException extends RuntimeException {

    private final TenantAction action;
    private final TenantStatus current;

    public IllegalTenantTransitionException(TenantAction action, TenantStatus current, String reason) {
        super("Cannot " + action.name().toLowerCase() + " a tenant that is " + current.name() + reason);
        this.action = action;
        this.current = current;
    }

    public TenantAction getAction() {
        return action;
    }

    public TenantStatus getCurrent() {
        return current;
    }
}
