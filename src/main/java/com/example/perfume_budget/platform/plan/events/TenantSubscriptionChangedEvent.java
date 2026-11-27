package com.example.perfume_budget.platform.plan.events;

/**
 * Published INSIDE the transaction of every tenant plan / trial / subscription-status change
 * ({@code PUT /platform/tenants/{slug}/plan}). The PLAN-02 tenant cache ({@code plans:v1:tenant:{tenantId}}, keyed by the
 * immutable tenant id, never the slug) evicts on it with {@code @TransactionalEventListener(phase = AFTER_COMMIT)}.
 */
public record TenantSubscriptionChangedEvent(Long tenantId) {
}
