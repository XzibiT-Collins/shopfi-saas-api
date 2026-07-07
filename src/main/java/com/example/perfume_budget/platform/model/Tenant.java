package com.example.perfume_budget.platform.model;

import com.example.perfume_budget.platform.enums.TenantPlan;
import com.example.perfume_budget.platform.enums.TenantStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Tenant registry row. Lives in the {@code master} schema (schema-qualified) so it
 * resolves regardless of the request's tenant search_path.
 *
 * <p>{@code slug} is the public identifier (host/JWT); {@code schemaName} is the
 * physical PostgreSQL schema — kept distinct on purpose.
 */
@Getter
@Setter
@Builder
@Entity
@Table(name = "tenants", schema = "master")
@AllArgsConstructor
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Pattern(regexp = "^[a-z0-9-]{3,40}$")
    @Column(nullable = false, unique = true, length = 40)
    private String slug;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "schema_name", nullable = false, unique = true, length = 63)
    private String schemaName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TenantStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TenantPlan plan;

    @Email
    @Column(name = "owner_email", nullable = false, unique = true)
    private String ownerEmail;

    @Column(name = "custom_domain", unique = true)
    private String customDomain;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
