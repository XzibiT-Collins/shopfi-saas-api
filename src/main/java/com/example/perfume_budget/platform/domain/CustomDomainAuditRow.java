package com.example.perfume_budget.platform.domain;

import com.example.perfume_budget.platform.model.TenantDomainAuditLog;

import java.time.Instant;

/** One row of master.tenant_domain_audit_log for the platform console (no token, no secrets). */
public record CustomDomainAuditRow(Long id, String action, String actorType, String actorEmail, String domain,
                                   String previousDomain, String previousStatus, String newStatus, String detail,
                                   Instant occurredAt) {

    public static CustomDomainAuditRow from(TenantDomainAuditLog a) {
        return new CustomDomainAuditRow(a.getId(), a.getAction(), a.getActorType(), a.getActorEmail(), a.getDomain(),
                a.getPreviousDomain(), a.getPreviousStatus(), a.getNewStatus(), a.getDetail(), a.getOccurredAt());
    }
}
