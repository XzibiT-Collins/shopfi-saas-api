package com.example.perfume_budget.platform.repository;

import com.example.perfume_budget.platform.model.TenantDomainAuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.List;

/** Insert and read only (no update/delete methods on purpose; the table is append-only). */
public interface TenantDomainAuditLogRepository extends Repository<TenantDomainAuditLog, Long> {

    TenantDomainAuditLog save(TenantDomainAuditLog row);

    List<TenantDomainAuditLog> findByTenantIdOrderByIdDesc(Long tenantId, Pageable pageable);

    List<TenantDomainAuditLog> findByTenantIdOrderByIdAsc(Long tenantId);
}
