package com.example.perfume_budget.platform.service;

/** The saga ran for an approve/reprovision and failed; the tenant is FAILED and can be reprovisioned (HTTP 500). */
public class TenantProvisioningFailedException extends RuntimeException {
    public TenantProvisioningFailedException(String slug) {
        super("Provisioning failed for tenant '" + slug + "'; it is now FAILED and can be reprovisioned");
    }
}
