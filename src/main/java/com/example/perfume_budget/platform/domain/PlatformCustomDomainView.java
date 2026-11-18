package com.example.perfume_budget.platform.domain;

import java.util.List;

/** Platform console view of one tenant's custom domain: current state plus the newest audit rows (max 50). */
public record PlatformCustomDomainView(String tenantSlug, CustomDomainView customDomain,
                                       List<CustomDomainAuditRow> history) {
}
