package com.example.perfume_budget.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * A tenant's storefront settings; today the theme document (see docs/api/theme-contract.md). Lives in
 * the tenant schema (no {@code schema=}, follows search_path). Singleton row per tenant, seeded with the
 * contract default by Flyway tenant V8. {@code version} is an optimistic-lock counter (JPA
 * {@link Version}) that the API layer maps to ETag / If-Match; a stale write fails with
 * {@code ObjectOptimisticLockingFailureException}.
 */
@Getter
@Setter
@Builder
@Entity
@Table(name = "store_settings")
@AllArgsConstructor
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class StoreSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    /** The full theme document (tokens, assets, pages, schemaVersion), stored as JSONB. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode theme;

    @Version
    @Column(nullable = false)
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;
}
