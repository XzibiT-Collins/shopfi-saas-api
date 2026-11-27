package com.example.perfume_budget.platform.dto;

import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.plan.model.Plan;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Tenant registry fields only (master.tenants). Deliberately omits {@code schemaName} and {@code webhookToken}
 * (internal/secret) and carries nothing from any tenant schema. {@code customDomainStatus} (DOMAIN-02) is
 * PENDING/VERIFIED/FAILED, null when no custom domain is set; only a VERIFIED domain is live.
 *
 * <p>PLAN-01 (platform-tenants.md "Plan and subscription fields"): {@code plan} is the plan CODE (still a string),
 * {@code planName} its current name, {@code subscriptionStatus} TRIALING / ACTIVE / PAST_DUE / CANCELED, {@code trialEndsAt}
 * an instant ending in Z or null until provisioning completes.
 */
public record PlatformTenantSummary(Long id, String slug, String displayName, String status, String plan, String planName,
                                    String subscriptionStatus, Instant trialEndsAt,
                                    String ownerEmail, String customDomain, String customDomainStatus,
                                    LocalDateTime createdAt,
                                    LocalDateTime updatedAt) {

    /** {@code plan} must be the tenant's plan ({@code t.getPlanId()}). */
    public static PlatformTenantSummary from(Tenant t, Plan plan) {
        if (plan == null || !plan.getId().equals(t.getPlanId())) {
            throw new IllegalArgumentException("the tenant's own plan is required");
        }
        return new PlatformTenantSummary(t.getId(), t.getSlug(), t.getDisplayName(), t.getStatus().name(),
                plan.getCode(), plan.getName(), t.getSubscriptionStatus().name(), t.getTrialEndsAt(),
                t.getOwnerEmail(), t.getCustomDomain(),
                t.getCustomDomainStatus() == null ? null : t.getCustomDomainStatus().name(), t.getCreatedAt(), t.getUpdatedAt());
    }
}
