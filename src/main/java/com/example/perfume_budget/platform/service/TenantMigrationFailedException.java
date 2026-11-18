package com.example.perfume_budget.platform.service;

/**
 * EVT-01c: a reactivation could not bring the tenant's schema up to date; the tenant stays SUSPENDED (HTTP 500) and
 * reactivate can be called again. No internals in the message.
 */
public class TenantMigrationFailedException extends RuntimeException {
    public TenantMigrationFailedException(String slug) {
        super("Tenant '" + slug + "' could not be brought up to date; it stays SUSPENDED and reactivate can be retried");
    }
}
