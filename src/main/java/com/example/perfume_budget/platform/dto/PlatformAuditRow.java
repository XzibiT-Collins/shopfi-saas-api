package com.example.perfume_budget.platform.dto;

import com.example.perfume_budget.platform.model.TenantAuditLog;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * One row of master.tenant_audit_log as the console may see it (PLAT-04). Explicit allow-list: no internal admin id,
 * no tenant id, nothing else. The table itself holds no password, hash, token or payload.
 * {@code occurredAt} is UTC with an explicit offset ({@code ...Z}).
 */
public record PlatformAuditRow(Long id, String tenantSlug, String action, String phase, String actorEmail,
                               String previousStatus, String newStatus, OffsetDateTime occurredAt) {

    public static PlatformAuditRow from(TenantAuditLog r) {
        return new PlatformAuditRow(r.getId(), r.getTenantSlug(), r.getAction().name(), r.getPhase().name(),
                r.getActorEmail(), r.getPreviousStatus().name(), r.getNewStatus().name(),
                r.getOccurredAt() == null ? null : r.getOccurredAt().atOffset(ZoneOffset.UTC));
    }
}
