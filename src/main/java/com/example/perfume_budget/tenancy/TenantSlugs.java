package com.example.perfume_budget.tenancy;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Slug normalisation and validation for provisioning. The slug becomes a PostgreSQL schema name
 * ({@code tenant_<slug>}) that is interpolated into DDL, so this is deliberately an allow-list
 * (never a "sanitise by stripping"): anything outside the pattern is rejected, not repaired.
 */
public final class TenantSlugs {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 40;
    public static final String SCHEMA_PREFIX = "tenant_";

    /** Lowercase alphanumerics with single interior hyphens, 3-40 chars. */
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private static final Set<String> RESERVED = Set.of(
            "public", "master", "tenant_template", "tenant-template", "template", "tenant", "tenants",
            "www", "api", "admin", "platform", "postgres", "information-schema", "information_schema",
            "root", "system", "internal");

    private TenantSlugs() {
    }

    /** Trims and lowercases (ROOT locale), then validates. @throws InvalidProvisioningRequestException if rejected. */
    public static String normalize(String raw) {
        if (raw == null) {
            throw new InvalidProvisioningRequestException("slug is required");
        }
        String slug = raw.strip().toLowerCase(Locale.ROOT);
        if (slug.length() < MIN_LENGTH || slug.length() > MAX_LENGTH) {
            throw new InvalidProvisioningRequestException(
                    "slug must be " + MIN_LENGTH + "-" + MAX_LENGTH + " characters");
        }
        if (!SLUG.matcher(slug).matches()) {
            throw new InvalidProvisioningRequestException(
                    "slug may contain only lowercase letters, digits and single hyphens between them");
        }
        String asSchemaPart = slug.replace('-', '_');
        if (RESERVED.contains(slug) || RESERVED.contains(asSchemaPart)
                || asSchemaPart.startsWith("pg_") || asSchemaPart.startsWith("tenant_")) {
            throw new InvalidProvisioningRequestException("slug is reserved");
        }
        return slug;
    }

    /** {@code acme-co} -> {@code tenant_acme_co}. Input must already be normalised. */
    public static String schemaNameFor(String normalizedSlug) {
        return SCHEMA_PREFIX + normalizedSlug.replace('-', '_');
    }
}
