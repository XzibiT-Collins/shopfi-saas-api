package com.example.perfume_budget.tenancy;

/**
 * Distinguishes a request bound to a specific tenant schema from one that operates
 * on the shared {@code master} control-plane schema (platform administration,
 * tenant onboarding). Platform-scoped requests never adopt a tenant search_path.
 */
public enum TenantScope {
    TENANT,
    PLATFORM
}
