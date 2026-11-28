package com.example.perfume_budget.platform.billing.settings;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * One row of {@code master.billing_settings_audit_log} (billing.md 4.6). Append-only: {@link Immutable}, a repository
 * without update or delete, and database triggers that refuse UPDATE, DELETE and TRUNCATE. {@code changes} is
 * {@code [{field, before, after}]}; credential fields are "unset" / "changed" / last 4 only.
 */
@Getter
@Builder
@Entity
@Immutable
@Table(name = "billing_settings_audit_log", schema = "master")
@AllArgsConstructor
@NoArgsConstructor
public class BillingSettingsAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Database time of the write; read back, never written by the application. */
    @Column(name = "occurred_at", nullable = false, insertable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "actor_admin_id", nullable = false, updatable = false)
    private Long actorAdminId;

    @Column(name = "actor_email", nullable = false, updatable = false)
    private String actorEmail;

    @Column(name = "settings_version", nullable = false, updatable = false)
    private int settingsVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private JsonNode changes;
}
