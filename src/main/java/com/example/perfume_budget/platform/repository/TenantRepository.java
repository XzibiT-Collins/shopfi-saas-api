package com.example.perfume_budget.platform.repository;

import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TenantRepository extends JpaRepository<Tenant, Long>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<Tenant> {

    Optional<Tenant> findBySlug(String slug);

    /**
     * Row lock on the tenant (SELECT ... FOR UPDATE), API-04 rework: webhook-token rotation and first-time minting
     * serialise on it, so two concurrent rotations cannot both answer with a URL (the loser's would already be dead).
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Tenant t where t.slug = :slug")
    Optional<Tenant> lockBySlug(@Param("slug") String slug);

    /**
     * DOMAIN-02: THE lookup that honours custom domains. Only a VERIFIED domain matches; PENDING/FAILED claims never
     * resolve a tenant. {@code host} must already be lower case (it hits the {@code lower(custom_domain)} unique index).
     */
    @Query("select t from Tenant t where lower(t.customDomain) = :host "
            + "and t.customDomainStatus = com.example.perfume_budget.platform.enums.CustomDomainStatus.VERIFIED")
    Optional<Tenant> findByVerifiedCustomDomain(@Param("host") String host);

    /** DOMAIN-02 write path only (conflict check before a claim): the owner of a domain in ANY status. */
    @Query("select t from Tenant t where lower(t.customDomain) = :host")
    Optional<Tenant> findCustomDomainClaim(@Param("host") String host);

    /**
     * DOMAIN-02 anti-squatting: clears another tenant's PENDING/FAILED claim that is older than {@code cutoff}. A
     * VERIFIED domain never matches. Returns 1 when released.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Tenant t set t.customDomain = null, t.customDomainStatus = null, t.customDomainToken = null, "
            + "t.customDomainRequestedAt = null, t.customDomainCheckedAt = null, t.customDomainVerifiedAt = null, "
            + "t.customDomainFailure = null, t.updatedAt = CURRENT_TIMESTAMP "
            + "where t.id = :id and lower(t.customDomain) = :host "
            + "and t.customDomainStatus <> com.example.perfume_budget.platform.enums.CustomDomainStatus.VERIFIED "
            + "and t.customDomainRequestedAt < :cutoff")
    int releaseStaleClaim(@Param("id") Long id, @Param("host") String host, @Param("cutoff") java.time.Instant cutoff);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Tenant t where t.id = :id")
    Optional<Tenant> lockById(@Param("id") Long id);

    Optional<Tenant> findBySchemaName(String schemaName);

    Optional<Tenant> findByWebhookToken(String webhookToken);

    boolean existsBySlug(String slug);

    boolean existsByOwnerEmail(String ownerEmail);

    List<Tenant> findByStatus(TenantStatus status);

    /** Registry page (PLAT-02). Reads {@code master.tenants} only, never a tenant schema. */
    org.springframework.data.domain.Page<Tenant> findByStatus(TenantStatus status,
                                                            org.springframework.data.domain.Pageable pageable);

    /**
     * PLAT-02: a PROVISIONING row whose saga died (crash/kill) is moved to FAILED so it can be reprovisioned; only
     * rows untouched since {@code cutoff} qualify, and the compare-and-set lets exactly one caller win.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Tenant t set t.status = com.example.perfume_budget.platform.enums.TenantStatus.FAILED, "
            + "t.updatedAt = CURRENT_TIMESTAMP where t.id = :id "
            + "and t.status = com.example.perfume_budget.platform.enums.TenantStatus.PROVISIONING "
            + "and t.updatedAt < :cutoff")
    int reclaimStuckProvisioning(@Param("id") Long id, @Param("cutoff") java.time.LocalDateTime cutoff);

    /**
     * Atomic compare-and-set used by provisioning to claim a PENDING/FAILED row; returns the number of
     * rows moved (0 = someone else owns it, so concurrent retries cannot both proceed).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Tenant t set t.status = :to, t.updatedAt = CURRENT_TIMESTAMP "
            + "where t.id = :id and t.status in :from")
    int transitionStatus(@Param("id") Long id, @Param("from") java.util.Collection<TenantStatus> from,
                         @Param("to") TenantStatus to);

    /** Stale PENDING (never verified) registrations are purged so an abandoned signup cannot hold a slug/email forever. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Tenant t where t.status = com.example.perfume_budget.platform.enums.TenantStatus.PENDING "
            + "and t.createdAt < :cutoff and (t.slug = :slug or t.ownerEmail = :email)")
    int deleteStalePending(@Param("slug") String slug, @Param("email") String email,
                           @Param("cutoff") java.time.LocalDateTime cutoff);
}
