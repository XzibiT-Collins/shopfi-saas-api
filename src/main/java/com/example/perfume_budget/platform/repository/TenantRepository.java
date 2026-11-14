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
public interface TenantRepository extends JpaRepository<Tenant, Long> {

    Optional<Tenant> findBySlug(String slug);

    Optional<Tenant> findByCustomDomain(String customDomain);

    Optional<Tenant> findBySchemaName(String schemaName);

    Optional<Tenant> findByWebhookToken(String webhookToken);

    boolean existsBySlug(String slug);

    boolean existsByOwnerEmail(String ownerEmail);

    List<Tenant> findByStatus(TenantStatus status);

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
