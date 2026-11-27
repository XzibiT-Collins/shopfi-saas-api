package com.example.perfume_budget.platform.plan.dto;

/** One feature of one plan (schema {@code Entitlement}). LIMIT: {@code limitValue} null = unlimited. */
public record EntitlementView(String featureKey, boolean enabled, Integer limitValue) {
}
