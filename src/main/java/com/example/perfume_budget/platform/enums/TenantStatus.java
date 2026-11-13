package com.example.perfume_budget.platform.enums;

/**
 * Lifecycle of a tenant. Doubles as saga state for provisioning
 * (PROVISIONING → ACTIVE / FAILED).
 */
public enum TenantStatus {
    PENDING,
    PROVISIONING,
    ACTIVE,
    SUSPENDED,
    FAILED
}
