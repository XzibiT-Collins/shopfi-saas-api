package com.example.perfume_budget.entitlement;

import com.example.perfume_budget.platform.plan.dto.EntitlementView;
import com.example.perfume_budget.platform.plan.dto.FeatureView;
import com.example.perfume_budget.platform.plan.dto.PublicPlanView;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/v1/admin/subscription} (docs/api/plans.md 13, schema {@code Subscription}). {@code plan} and
 * {@code publicPlans} are the separate PUBLIC plan type (no version, updatedBy, tenant counts; Security requirement 2).
 */
@JsonPropertyOrder({"plan", "status", "trialEndsAt", "readOnly", "entitlements", "usage", "features", "publicPlans"})
public record SubscriptionView(
        PublicPlanView plan,
        String status,
        Instant trialEndsAt,
        boolean readOnly,
        List<EntitlementView> entitlements,
        List<Usage> usage,
        List<FeatureView> features,
        List<PublicPlanView> publicPlans) {

    /** One LIMIT feature: {@code limit} null = unlimited; {@code used} counted over the counted set of 15.2. */
    @JsonPropertyOrder({"featureKey", "used", "limit"})
    public record Usage(String featureKey, long used, Integer limit) {
    }
}
