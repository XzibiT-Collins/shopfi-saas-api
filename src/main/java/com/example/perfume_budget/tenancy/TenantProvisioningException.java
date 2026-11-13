package com.example.perfume_budget.tenancy;

/** Provisioning failed after the registry row was created; compensation ran and the tenant is FAILED (retryable). */
public class TenantProvisioningException extends RuntimeException {

    private final String slug;
    private final boolean schemaCleanedUp;

    public TenantProvisioningException(String slug, boolean schemaCleanedUp, Throwable cause) {
        super("Provisioning failed for tenant '" + slug + "'"
                + (schemaCleanedUp ? "" : " (schema cleanup also failed; manual DROP may be required)"), cause);
        this.slug = slug;
        this.schemaCleanedUp = schemaCleanedUp;
    }

    public String getSlug() {
        return slug;
    }

    public boolean isSchemaCleanedUp() {
        return schemaCleanedUp;
    }
}
