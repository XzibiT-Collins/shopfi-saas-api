package com.example.perfume_budget.tenancy;

/**
 * Typed tenant identity carried on the request thread.
 *
 * <p>Deliberately separates the two identifiers that were previously easy to confuse:
 * <ul>
 *   <li>{@code slug} — the public, host/JWT identifier (e.g. {@code acme}); {@code null} when neutral.</li>
 *   <li>{@code schemaName} — the physical PostgreSQL schema Hibernate switches to (e.g. {@code tenant_acme}).</li>
 * </ul>
 */
public record TenantIdentity(String slug, String schemaName, TenantScope scope) {

    /** A resolved tenant: real slug + its physical schema. */
    public static TenantIdentity tenant(String slug, String schemaName) {
        return new TenantIdentity(slug, schemaName, TenantScope.TENANT);
    }

    /** Platform/master control-plane scope; connection stays on the neutral schema. */
    public static TenantIdentity platform() {
        return new TenantIdentity(TenantContext.PLATFORM_SLUG, TenantContext.DEFAULT_SCHEMA, TenantScope.PLATFORM);
    }

    /** Neutral fallback used before/without resolution and for Hibernate {@code validate}. */
    public static TenantIdentity template() {
        return new TenantIdentity(null, TenantContext.DEFAULT_SCHEMA, TenantScope.TENANT);
    }
}
