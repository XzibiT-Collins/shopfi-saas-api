package com.example.perfume_budget.platform.dto;

import com.example.perfume_budget.platform.model.Tenant;

import java.time.LocalDateTime;

/**
 * Tenant registry fields only (master.tenants). Deliberately omits {@code schemaName} and {@code webhookToken}
 * (internal/secret) and carries nothing from any tenant schema. {@code customDomainStatus} (DOMAIN-02) is
 * PENDING/VERIFIED/FAILED, null when no custom domain is set; only a VERIFIED domain is live.
 */
public record PlatformTenantSummary(Long id, String slug, String displayName, String status, String plan,
                                    String ownerEmail, String customDomain, String customDomainStatus,
                                    LocalDateTime createdAt,
                                    LocalDateTime updatedAt) {

    public static PlatformTenantSummary from(Tenant t) {
        return new PlatformTenantSummary(t.getId(), t.getSlug(), t.getDisplayName(), t.getStatus().name(),
                t.getPlan().name(), t.getOwnerEmail(), t.getCustomDomain(),
                t.getCustomDomainStatus() == null ? null : t.getCustomDomainStatus().name(), t.getCreatedAt(), t.getUpdatedAt());
    }
}
