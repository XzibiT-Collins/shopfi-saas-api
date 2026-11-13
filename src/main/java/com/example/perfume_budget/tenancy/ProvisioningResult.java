package com.example.perfume_budget.tenancy;

import com.example.perfume_budget.platform.enums.TenantStatus;

/** Outcome of a successful provisioning run (status is always ACTIVE; failures throw). */
public record ProvisioningResult(Long tenantId, String slug, String schemaName, TenantStatus status) {
}
