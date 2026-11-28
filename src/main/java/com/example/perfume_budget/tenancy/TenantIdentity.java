package com.example.perfume_budget.tenancy;

import java.util.Objects;

/**
 * Typed tenant identity carried on the request thread.
 *
 * <p>Deliberately separates the two identifiers that were previously easy to confuse:
 * <ul>
 *   <li>{@code slug} — the public, host/JWT identifier (e.g. {@code acme}); {@code null} when neutral.</li>
 *   <li>{@code schemaName} — the physical PostgreSQL schema Hibernate switches to (e.g. {@code tenant_acme}).</li>
 * </ul>
 *
 * <p>PLAN-02: {@code tenantId} is the immutable master id of the tenant row the slug was resolved from (null when the
 * identity was built without the row, e.g. in older call sites or tests). It is a lookup hint for master-keyed caches
 * (plans.md 15.1: keys by immutable id, never by slug) and is deliberately NOT part of equality: two identities of the
 * same slug, schema and scope are the same tenant binding.
 */
public record TenantIdentity(String slug, String schemaName, TenantScope scope, Long tenantId) {

    public TenantIdentity(String slug, String schemaName, TenantScope scope) {
        this(slug, schemaName, scope, null);
    }

    /** A resolved tenant: real slug + its physical schema. */
    public static TenantIdentity tenant(String slug, String schemaName) {
        return new TenantIdentity(slug, schemaName, TenantScope.TENANT, null);
    }

    /** A resolved tenant with its immutable master id (PLAN-02). */
    public static TenantIdentity tenant(Long tenantId, String slug, String schemaName) {
        return new TenantIdentity(slug, schemaName, TenantScope.TENANT, tenantId);
    }

    /** Platform/master control-plane scope; connection stays on the neutral schema. */
    public static TenantIdentity platform() {
        return new TenantIdentity(TenantContext.PLATFORM_SLUG, TenantContext.DEFAULT_SCHEMA, TenantScope.PLATFORM, null);
    }

    /** Neutral fallback used before/without resolution and for Hibernate {@code validate}. */
    public static TenantIdentity template() {
        return new TenantIdentity(null, TenantContext.DEFAULT_SCHEMA, TenantScope.TENANT, null);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TenantIdentity other && Objects.equals(slug, other.slug)
                && Objects.equals(schemaName, other.schemaName) && scope == other.scope;
    }

    @Override
    public int hashCode() {
        return Objects.hash(slug, schemaName, scope);
    }
}
