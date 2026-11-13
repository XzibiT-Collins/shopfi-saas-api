package com.example.perfume_budget.platform.service;

import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;

import java.util.List;
import java.util.Optional;

/**
 * Explicit read access to the master tenant registry. Kept separate from tenant-local
 * services so master queries never depend on the request's tenant search_path.
 */
public interface TenantRegistryService {

    Optional<Tenant> findBySlug(String slug);

    Optional<Tenant> findByCustomDomain(String customDomain);

    Optional<Tenant> findByWebhookToken(String webhookToken);

    List<Tenant> findByStatus(TenantStatus status);

    boolean slugExists(String slug);

    boolean ownerEmailExists(String ownerEmail);
}
