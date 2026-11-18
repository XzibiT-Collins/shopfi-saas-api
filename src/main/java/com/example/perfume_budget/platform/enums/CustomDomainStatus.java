package com.example.perfume_budget.platform.enums;

/**
 * Verification state of a tenant's custom domain (DOMAIN-02). Only {@link #VERIFIED} domains are honoured by tenant
 * resolution, CORS, OAuth2 redirect checks and emailed links ({@code TenantHostLookup}, {@code Tenant#verifiedCustomDomain}).
 */
public enum CustomDomainStatus {
    /** Claimed by the tenant; the DNS TXT proof has not been checked yet. */
    PENDING,
    /** The DNS TXT proof was found at the last check. */
    VERIFIED,
    /** The last check did not find the proof (or the stored domain no longer passes validation). */
    FAILED
}
