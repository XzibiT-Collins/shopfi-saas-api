package com.example.perfume_budget.platform.model;

import com.example.perfume_budget.platform.enums.CustomDomainStatus;
import com.example.perfume_budget.platform.enums.TenantPlan;
import com.example.perfume_budget.platform.enums.TenantStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

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

    /**
     * The tenant's own storefront host (DOMAIN-02), lower case, no trailing dot. Written only by
     * {@code CustomDomainService} after {@code CustomDomains} validation. Unique across tenants case-insensitively
     * (master index {@code uq_tenants_custom_domain_lower}). Honoured ONLY while {@link #customDomainStatus} is
     * VERIFIED: read it through {@link #verifiedCustomDomain()} or {@code TenantRegistryService#findByVerifiedCustomDomain}.
     */
    @Column(name = "custom_domain")
    private String customDomain;

    @Enumerated(EnumType.STRING)
    @Column(name = "custom_domain_status", length = 20)
    private CustomDomainStatus customDomainStatus;

    /** Random value the tenant publishes in DNS (TXT) to prove control of {@link #customDomain}. Not a secret. */
    @Column(name = "custom_domain_token", length = 64)
    private String customDomainToken;

    @Column(name = "custom_domain_requested_at")
    private Instant customDomainRequestedAt;

    @Column(name = "custom_domain_checked_at")
    private Instant customDomainCheckedAt;

    @Column(name = "custom_domain_verified_at")
    private Instant customDomainVerifiedAt;

    /** Machine-readable reason of the last failed check (e.g. {@code TXT_RECORD_NOT_FOUND}); null otherwise. */
    @Column(name = "custom_domain_failure", length = 40)
    private String customDomainFailure;

    /** Opaque token embedded in this tenant's Paystack webhook URL (/api/v1/webhook/{token}). */
    @Column(name = "webhook_token", unique = true, length = 64)
    private String webhookToken;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** The custom domain only when it is VERIFIED; a PENDING or FAILED claim is never honoured (DOMAIN-02 AC2). */
    public Optional<String> verifiedCustomDomain() {
        if (customDomainStatus != CustomDomainStatus.VERIFIED || customDomain == null || customDomain.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(customDomain.strip().toLowerCase(java.util.Locale.ROOT));
    }

    /** Clears the custom domain and every verification field (removal, release). */
    public void clearCustomDomain() {
        customDomain = null;
        customDomainStatus = null;
        customDomainToken = null;
        customDomainRequestedAt = null;
        customDomainCheckedAt = null;
        customDomainVerifiedAt = null;
        customDomainFailure = null;
    }
}
