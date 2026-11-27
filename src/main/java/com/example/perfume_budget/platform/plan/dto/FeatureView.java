package com.example.perfume_budget.platform.plan.dto;

import com.example.perfume_budget.platform.plan.PlanFeature;

/** A catalog entry (schema {@code Feature}). */
public record FeatureView(String key, String kind, String label, String group) {

    public static FeatureView of(PlanFeature f) {
        return new FeatureView(f.name(), f.kind().name(), f.label(), f.group());
    }
}
