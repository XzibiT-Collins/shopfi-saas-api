package com.example.perfume_budget.platform.enums;

/**
 * Which step of a platform action an audit row records (PLAT-02b). Rows are append-only, so a multi-step action writes
 * several rows. See {@code V7__tenant_audit_log_phase.sql}.
 */
public enum TenantAuditPhase {
    /** approve/reprovision accepted (previous -> PROVISIONING), committed with the saga claim, before any DDL. */
    INTENT,
    /** the action finished (previous -> final status); also the only row of suspend/reactivate. */
    OUTCOME,
    /** stuck PROVISIONING moved to FAILED (PROVISIONING -> FAILED). */
    RECLAIM,
    /** a retry on an ACTIVE tenant repaired a leftover (signup hash, or a missing OUTCOME): ACTIVE -> ACTIVE. */
    RECONCILE
}
