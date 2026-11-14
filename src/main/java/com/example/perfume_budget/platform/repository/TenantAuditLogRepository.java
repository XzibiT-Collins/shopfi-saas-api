package com.example.perfume_budget.platform.repository;

import com.example.perfume_budget.platform.model.TenantAuditLog;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Deliberately NOT a JpaRepository: only insert and read exist, so no code path can update or delete audit rows.
 */
@org.springframework.stereotype.Repository
public interface TenantAuditLogRepository extends Repository<TenantAuditLog, Long> {

    TenantAuditLog saveAndFlush(TenantAuditLog entry);

    List<TenantAuditLog> findByTenantSlugOrderByIdAsc(String tenantSlug);

    long count();

    /** Newest row of a phase for a tenant (used to detect an INTENT that never got its OUTCOME). */
    Optional<TenantAuditLog> findFirstByTenantIdAndPhaseOrderByIdDesc(Long tenantId,
                                                                      com.example.perfume_budget.platform.enums.TenantAuditPhase phase);

    /** True if an OUTCOME-style row of exactly this action and starting status exists after {@code id}. */
    boolean existsByTenantIdAndPhaseAndActionAndPreviousStatusAndIdGreaterThan(
            Long tenantId, com.example.perfume_budget.platform.enums.TenantAuditPhase phase,
            com.example.perfume_budget.platform.enums.TenantAction action,
            com.example.perfume_budget.platform.enums.TenantStatus previousStatus, Long id);

    boolean existsByTenantIdAndPhaseAndIdGreaterThan(Long tenantId,
                                                     com.example.perfume_budget.platform.enums.TenantAuditPhase phase, Long id);
}
