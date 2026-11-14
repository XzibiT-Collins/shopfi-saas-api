package com.example.perfume_budget.platform.dto;

/**
 * Result of an approve/suspend/reactivate/reprovision call. {@code changed=false} means an idempotent no-op (the
 * tenant was already in the requested state or the provisioning saga is already running: {@code inProgress}).
 */
public record TenantActionResponse(String action, boolean changed, boolean inProgress, String previousStatus,
                                   PlatformTenantSummary tenant) {
}
