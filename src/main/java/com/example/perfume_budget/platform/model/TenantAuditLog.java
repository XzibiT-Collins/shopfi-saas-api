package com.example.perfume_budget.platform.model;

import com.example.perfume_budget.platform.enums.TenantAction;
import com.example.perfume_budget.platform.enums.TenantAuditPhase;
import com.example.perfume_budget.platform.enums.TenantStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * One platform-admin action on a tenant (master.tenant_audit_log). Append-only: the entity is {@link Immutable},
 * has no setters, its repository exposes no update/delete, and the database rejects UPDATE/DELETE/TRUNCATE.
 * Carries no secrets by construction (no password, hash, token or payload fields).
 */
@Getter
@Builder
@Immutable
@Entity
@Table(name = "tenant_audit_log", schema = "master")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TenantAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false, insertable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "actor_admin_id", nullable = false, updatable = false)
    private Long actorAdminId;

    @Column(name = "actor_email", nullable = false, updatable = false)
    private String actorEmail;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "tenant_slug", nullable = false, updatable = false, length = 40)
    private String tenantSlug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private TenantAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", nullable = false, updatable = false, length = 20)
    private TenantStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, updatable = false, length = 20)
    private TenantStatus newStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private TenantAuditPhase phase;
}
