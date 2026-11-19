package com.example.perfume_budget.platform.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * One custom-domain change (master.tenant_domain_audit_log, DOMAIN-02 AC2). Append-only like {@link TenantAuditLog}:
 * immutable entity, insert-only repository, UPDATE/DELETE/TRUNCATE rejected by database triggers. Carries no token.
 */
@Getter
@Builder
@Immutable
@Entity
@Table(name = "tenant_domain_audit_log", schema = "master")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TenantDomainAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false, insertable = false, updatable = false)
    private Instant occurredAt;

    /** TENANT_ADMIN, PLATFORM_ADMIN or SYSTEM. */
    @Column(name = "actor_type", nullable = false, updatable = false, length = 20)
    private String actorType;

    @Column(name = "actor_id", updatable = false)
    private Long actorId;

    @Column(name = "actor_email", updatable = false)
    private String actorEmail;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "tenant_slug", nullable = false, updatable = false, length = 40)
    private String tenantSlug;

    /** REQUEST, VERIFY, REMOVE or RELEASE. */
    @Column(nullable = false, updatable = false, length = 20)
    private String action;

    @Column(nullable = false, updatable = false, length = 253)
    private String domain;

    @Column(name = "previous_domain", updatable = false, length = 253)
    private String previousDomain;

    @Column(name = "previous_status", updatable = false, length = 20)
    private String previousStatus;

    @Column(name = "new_status", updatable = false, length = 20)
    private String newStatus;

    @Column(updatable = false, length = 40)
    private String detail;
}
