package com.example.perfume_budget.platform.plan.model;

import com.example.perfume_budget.platform.plan.PlanAuditAction;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * One row of the plan audit log (master.plan_audit_log, docs/api/plans.md section 10). Append-only: the entity is
 * {@link Immutable} (no setters, Hibernate never issues an UPDATE) and the table has triggers that refuse UPDATE, DELETE
 * and TRUNCATE. {@code changes} holds only changed fields as {@code [{field, before, after}]}.
 */
@Getter
@Builder
@Entity
@Immutable
@Table(name = "plan_audit_log", schema = "master")
@AllArgsConstructor
@NoArgsConstructor
public class PlanAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Database time of the write (transaction time); read back, never written by the application. */
    @Column(name = "occurred_at", nullable = false, insertable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "actor_admin_id", nullable = false, updatable = false)
    private Long actorAdminId;

    @Column(name = "actor_email", nullable = false, updatable = false)
    private String actorEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private PlanAuditAction action;

    @Column(name = "plan_id", updatable = false)
    private Long planId;

    @Column(name = "plan_code", updatable = false, length = 32)
    private String planCode;

    @Column(name = "tenant_id", updatable = false)
    private Long tenantId;

    @Column(name = "tenant_slug", updatable = false, length = 40)
    private String tenantSlug;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private JsonNode changes;
}
