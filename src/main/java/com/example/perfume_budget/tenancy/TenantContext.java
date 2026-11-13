package com.example.perfume_budget.tenancy;

/**
 * Thread-bound holder for the current {@link TenantIdentity}.
 *
 * <p>Set as early as possible in the filter chain and always cleared in a
 * {@code finally} block. When unset, callers fall back to the neutral
 * {@link #DEFAULT_SCHEMA} so that no query is ever silently routed to a real
 * tenant's data by accident.
 *
 * <p>Off-request threads (@Async, @Scheduled, WebSocket) do NOT inherit this
 * ThreadLocal — context must be propagated explicitly (see AsyncConfig TaskDecorator
 * and TenantRunner in later sub-sections).
 */
public final class TenantContext {

    /** Neutral, non-tenant schema. Also the Hibernate {@code ddl-auto=validate} target. */
    public static final String DEFAULT_SCHEMA = "tenant_template";

    /** Slug marker used for platform/master-scoped tokens and cache keys. */
    public static final String PLATFORM_SLUG = "__platform__";

    private static final ThreadLocal<TenantIdentity> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(TenantIdentity identity) {
        CURRENT.set(identity);
    }

    public static TenantIdentity get() {
        return CURRENT.get();
    }

    public static TenantIdentity getOrDefault() {
        TenantIdentity identity = CURRENT.get();
        return identity != null ? identity : TenantIdentity.template();
    }

    /** Physical schema Hibernate should switch to for the current request. */
    public static String getSchemaOrDefault() {
        return getOrDefault().schemaName();
    }

    /** Public slug of the current tenant, or {@code null} when neutral. */
    public static String getSlug() {
        TenantIdentity identity = CURRENT.get();
        return identity != null ? identity.slug() : null;
    }

    /** Namespace prefix for cache keys: tenant slug, platform marker, or neutral schema. */
    public static String getCachePrefix() {
        TenantIdentity identity = CURRENT.get();
        if (identity == null) {
            return DEFAULT_SCHEMA;
        }
        return identity.scope() == TenantScope.PLATFORM ? PLATFORM_SLUG : identity.slug();
    }

    public static boolean isPlatform() {
        TenantIdentity identity = CURRENT.get();
        return identity != null && identity.scope() == TenantScope.PLATFORM;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
