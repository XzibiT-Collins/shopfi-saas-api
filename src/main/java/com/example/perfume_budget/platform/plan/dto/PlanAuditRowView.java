package com.example.perfume_budget.platform.plan.dto;

import com.example.perfume_budget.platform.plan.model.PlanAuditLog;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * One plan audit row (schema {@code PlanAuditRow}), exactly these keys. The actor's immutable admin id and the tenant id
 * are stored but never returned.
 */
public record PlanAuditRowView(long id, Instant occurredAt, String actorEmail, String action, Long planId,
                               String planCode, String tenantSlug, JsonNode changes) {

    public static PlanAuditRowView from(PlanAuditLog a) {
        return new PlanAuditRowView(a.getId(), a.getOccurredAt(), a.getActorEmail(), a.getAction().name(), a.getPlanId(),
                a.getPlanCode(), a.getTenantSlug(), a.getChanges());
    }
}
