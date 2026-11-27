package com.example.perfume_budget.platform.plan.repository;

import com.example.perfume_budget.platform.plan.model.PlanAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Append-only by construction: a plain {@link Repository} exposing insert and reads only (no delete, no update, no
 * saveAll of existing rows). The table's triggers refuse UPDATE / DELETE / TRUNCATE as a second line.
 */
public interface PlanAuditLogRepository extends Repository<PlanAuditLog, Long> {

    PlanAuditLog saveAndFlush(PlanAuditLog row);

    /** Newest first ({@code id} descending); both filters optional. */
    @Query("select a from PlanAuditLog a where (:planId is null or a.planId = :planId) "
            + "and (:tenantSlug is null or a.tenantSlug = :tenantSlug) order by a.id desc")
    Page<PlanAuditLog> search(@Param("planId") Long planId, @Param("tenantSlug") String tenantSlug, Pageable pageable);

    long count();
}
