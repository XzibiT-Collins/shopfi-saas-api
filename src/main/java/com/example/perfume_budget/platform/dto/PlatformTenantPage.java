package com.example.perfume_budget.platform.dto;

import java.util.List;

/** One page of the tenant registry. {@code size} is the EFFECTIVE (capped) page size. */
public record PlatformTenantPage(List<PlatformTenantSummary> items, int page, int size, long totalElements,
                                 int totalPages, String sort, String direction) {
}
