package com.example.perfume_budget.platform.dto;

import com.example.perfume_budget.platform.model.Tenant;

import java.time.LocalDateTime;

/**
 * Tenant registry fields only (master.tenants). Deliberately omits {@code schemaName} and {@code webhookToken}
 * (internal/secret) and carries nothing from any tenant schema.
 */
public record PlatformTenantSummary(Long id, String slug, String displayName, String status, String plan,
                                    String ownerEmail, String customDomain, LocalDateTime createdAt,
                                    LocalDateTime updatedAt) {

    public static PlatformTenantSummary from(Tenant t) {
        return new PlatformTenantSummary(t.getId(), t.getSlug(), t.getDisplayName(), t.getStatus().name(),
                t.getPlan().name(), t.getOwnerEmail(), t.getCustomDomain(), t.getCreatedAt(), t.getUpdatedAt());
    }
}
