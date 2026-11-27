package com.example.perfume_budget.platform.plan.dto;

import com.example.perfume_budget.dto.json.StrictIntegerDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Min;

import java.time.Instant;

/**
 * {@code PUT /api/v1/platform/tenants/{slug}/plan} (plans.md section 11). At least one of planId / trialEndsAt /
 * subscriptionStatus; trialEndsAt and subscriptionStatus never together; allowInactive only with planId. The combination
 * rules and the allowed status values are checked by the service (400 with a field map).
 */
public record TenantPlanUpdateRequest(
        @Min(1) @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer planId,
        Instant trialEndsAt,
        String subscriptionStatus,
        Boolean allowInactive) {
}
