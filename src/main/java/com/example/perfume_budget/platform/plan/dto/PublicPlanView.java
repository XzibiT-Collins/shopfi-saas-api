package com.example.perfume_budget.platform.plan.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.Instant;
import java.util.List;

/**
 * The PUBLIC plan shape (schema {@code PlanPublic}): the public list ({@code GET /platform/onboarding/plans}) and, from
 * PLAN-02, the subscription response. A separate type on purpose (Security requirement 2): it has no {@code version},
 * {@code updatedBy}, {@code tenantCounts} or audit data, so nothing a serializer setting changes can leak them.
 */
@JsonPropertyOrder({"id", "code", "name", "tagline", "description", "monthlyPricePesewas", "annualDiscountPercent",
        "annualPricePesewas", "annualMonthlyEquivalentPesewas", "annualSavingPesewas", "currency", "trialDays",
        "transactionFeeBps", "isPublic", "isDefaultForSignup", "highlighted", "badgeLabel", "bulletsHeading",
        "marketingBullets", "sortOrder", "active", "updatedAt", "entitlements"})
public record PublicPlanView(
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
        List<EntitlementView> entitlements) {
}
