package com.example.perfume_budget.platform.domain;

/** Body of {@code PUT /api/v1/admin/custom-domain}. Validation (with stable codes) is done by {@link CustomDomains}. */
public record CustomDomainRequest(String domain) {
}
