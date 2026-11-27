package com.example.perfume_budget.platform.plan.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The PLATFORM (SUPER_ADMIN) plan shape (schema {@code PlanPlatform}): the public fields plus {@code version} (the
 * optimistic-lock input), {@code updatedBy} (admin e-mail, SUPER_ADMIN data) and {@code tenantCounts}. Never returned by
 * a public or tenant endpoint; see {@link PublicPlanView}.
 */
@JsonPropertyOrder({"id", "code", "name", "tagline", "description", "monthlyPricePesewas", "annualDiscountPercent",
        "annualPricePesewas", "annualMonthlyEquivalentPesewas", "annualSavingPesewas", "currency", "trialDays",
        "transactionFeeBps", "isPublic", "isDefaultForSignup", "highlighted", "badgeLabel", "bulletsHeading",
        "marketingBullets", "sortOrder", "active", "updatedAt", "entitlements", "version", "updatedBy", "tenantCounts"})
public record PlanView(
        long id,
        String code,
        String name,
        String tagline,
        String description,
        long monthlyPricePesewas,
        int annualDiscountPercent,
        long annualPricePesewas,
        long annualMonthlyEquivalentPesewas,
        long annualSavingPesewas,
        String currency,
        int trialDays,
        int transactionFeeBps,
        @JsonProperty("isPublic") boolean isPublic,
        @JsonProperty("isDefaultForSignup") boolean isDefaultForSignup,
        boolean highlighted,
        String badgeLabel,
        String bulletsHeading,
        List<String> marketingBullets,
        int sortOrder,
        boolean active,
        Instant updatedAt,
        List<EntitlementView> entitlements,
        long version,
        String updatedBy,
        Map<String, Long> tenantCounts) {
}
