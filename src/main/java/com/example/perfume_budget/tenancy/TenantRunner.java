package com.example.perfume_budget.tenancy;

import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.service.TenantRegistryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Runs an action once per ACTIVE tenant with that tenant bound to {@link TenantContext}. Used by
 * scheduled jobs that would otherwise execute against a single (neutral) schema. A failure for
 * one tenant is logged and does not stop the others; context is always cleared afterwards.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantRunner {

    private final TenantRegistryService tenantRegistryService;

    public void forEachActiveTenant(Runnable action) {
        for (Tenant tenant : tenantRegistryService.findByStatus(TenantStatus.ACTIVE)) {
            try {
                TenantContext.set(TenantIdentity.tenant(tenant.getSlug(), tenant.getSchemaName()));
                action.run();
            } catch (Exception e) {
                log.error("Scheduled action failed for tenant {}: {}", tenant.getSlug(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
    }
}
