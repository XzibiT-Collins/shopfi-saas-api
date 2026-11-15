package com.example.perfume_budget.platform.dto;

import java.util.List;

/** One page of a tenant's audit trail, same paging envelope as {@link PlatformTenantPage}. */
public record PlatformAuditPage(List<PlatformAuditRow> items, int page, int size, long totalElements,
                                int totalPages, String sort, String direction) {
}
