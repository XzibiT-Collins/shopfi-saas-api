package com.example.perfume_budget.tenancy;

import com.example.perfume_budget.platform.enums.TenantPlan;

/**
 * Input to {@link TenantProvisioningService#provision}. {@code passwordHash} MUST already be a BCrypt hash
 * of the admin password (hash the exact string the admin will type at login, after the same strip() the
 * login path applies). The raw password never reaches this service.
 */
public record ProvisioningRequest(String slug, String displayName, String ownerEmail, String ownerFullName,
                                  String passwordHash, TenantPlan plan) {

    /** Never prints the hash. */
    @Override
    public String toString() {
        return "ProvisioningRequest[slug=" + slug + ", displayName=" + displayName + ", ownerEmail=" + ownerEmail
                + ", plan=" + plan + ", passwordHash=<redacted>]";
    }
}
