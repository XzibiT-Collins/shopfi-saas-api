package com.example.perfume_budget.tenancy;

/**
 * Input to {@link TenantProvisioningService#provision}. {@code passwordHash} MUST already be a BCrypt hash
 * of the admin password (hash the exact string the admin will type at login, after the same strip() the
 * login path applies). The raw password never reaches this service.
 */
public record ProvisioningRequest(String slug, String displayName, String ownerEmail, String ownerFullName,
                                  String passwordHash) {
    // PLAN-01: no plan field. A NEW registry row gets the is_default_for_signup plan inside the saga's claim transaction;
    // a claimed PENDING / FAILED row keeps the plan it already has.

    /** Never prints the hash. */
    @Override
    public String toString() {
        return "ProvisioningRequest[slug=" + slug + ", displayName=" + displayName + ", ownerEmail=" + ownerEmail
                + ", passwordHash=<redacted>]";
    }
}
