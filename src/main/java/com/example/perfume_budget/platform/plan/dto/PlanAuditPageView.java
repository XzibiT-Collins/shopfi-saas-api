package com.example.perfume_budget.platform.plan.dto;

import java.util.List;

/** {@code GET /platform/plans/audit}: one page, newest first. */
public record PlanAuditPageView(List<PlanAuditRowView> items, int page, int size, long totalElements, int totalPages) {
}
