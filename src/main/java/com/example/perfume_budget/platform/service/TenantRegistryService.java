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

    /**
     * DOMAIN-02: the tenant owning {@code host} as a VERIFIED custom domain. PENDING/FAILED claims are ignored. Callers
     * normally go through {@code TenantHostLookup}, which also keeps hosts under the base domain away from this lookup.
     */
    Optional<Tenant> findByVerifiedCustomDomain(String host);

    Optional<Tenant> findByWebhookToken(String webhookToken);

    List<Tenant> findByStatus(TenantStatus status);

    boolean slugExists(String slug);

    boolean ownerEmailExists(String ownerEmail);
}
