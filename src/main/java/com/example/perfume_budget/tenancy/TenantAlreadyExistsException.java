package com.example.perfume_budget.tenancy;

/** The slug or owner email is taken, or the tenant is not in a provisionable state (ACTIVE, PROVISIONING, SUSPENDED). */
public class TenantAlreadyExistsException extends RuntimeException {
    public TenantAlreadyExistsException(String message) {
        super(message);
    }
}
