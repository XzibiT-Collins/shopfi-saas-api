package com.example.perfume_budget.platform.dto;

import java.util.List;
import java.util.Map;

/**
 * One page of the tenant registry. {@code size} is the EFFECTIVE (capped) page size. {@code customDomainCounts}
 * (API-RD-07 AC2) is the page-header facet: tenants per custom-domain state ({@code PENDING}, {@code VERIFIED},
 * {@code FAILED}, {@code NONE} = no custom domain) among the tenants matching the {@code status} and {@code search}
 * filters, IGNORING the custom-domain filters and paging, so each count says how many rows that filter would show.
 */
public record PlatformTenantPage(List<PlatformTenantSummary> items, int page, int size, long totalElements,
                                 int totalPages, String sort, String direction, Map<String, Long> customDomainCounts) {
}
