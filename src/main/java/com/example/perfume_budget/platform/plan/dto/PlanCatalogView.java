package com.example.perfume_budget.platform.plan.dto;

import java.util.List;

/** {@code GET /platform/plans} and the matrix answer (schema {@code PlanCatalog}): every plan plus the catalog. */
public record PlanCatalogView(List<PlanView> plans, List<FeatureView> features) {
}
