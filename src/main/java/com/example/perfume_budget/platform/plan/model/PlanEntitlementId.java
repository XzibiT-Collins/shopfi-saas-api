package com.example.perfume_budget.platform.plan.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;

/** Primary key of {@link PlanEntitlement}: one row per (plan, feature key). */
@Embeddable
public record PlanEntitlementId(
        @Column(name = "plan_id", nullable = false) Long planId,
        @Column(name = "feature_key", nullable = false, length = 64) String featureKey) implements Serializable {
}
