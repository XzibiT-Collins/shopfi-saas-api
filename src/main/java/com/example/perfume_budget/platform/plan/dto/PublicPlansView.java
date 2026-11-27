package com.example.perfume_budget.platform.plan.dto;

import java.util.List;

/** {@code GET /platform/onboarding/plans}: public active plans (public shape) plus the catalog. */
public record PublicPlansView(List<PublicPlanView> plans, List<FeatureView> features) {
}
