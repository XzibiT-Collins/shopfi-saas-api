package com.example.perfume_budget.platform.repository;

import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
