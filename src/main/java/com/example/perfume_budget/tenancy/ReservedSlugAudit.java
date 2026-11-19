package com.example.perfume_budget.tenancy;

import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.repository.TenantRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * TENANT-14 audit: growing {@link TenantSlugs}' reserved list cannot rename tenants that already own such a slug
 * (their schema, links and custom data are keyed on it). At every start this lists them at ERROR so an operator
 * can rename them deliberately; it never changes data and never blocks startup. Only slugs are logged.
 */
@Slf4j
@Component
public class ReservedSlugAudit implements ApplicationRunner {

    private final TenantRepository tenants;

    public ReservedSlugAudit(TenantRepository tenants) {
        this.tenants = tenants;
    }

    /** Slugs of existing tenants that the current reserved list would refuse. */
    public List<String> findReservedSlugs() {
        return tenants.findAll().stream().map(Tenant::getSlug)
                .filter(slug -> slug != null && TenantSlugs.isReserved(slug)).sorted().toList();
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<String> reserved = findReservedSlugs();
            if (!reserved.isEmpty()) {
                log.error("Existing tenants own slugs that are now reserved and must be renamed by an operator: {}", reserved);
            }
        } catch (RuntimeException e) {
            log.warn("Reserved-slug audit skipped: {}", e.getClass().getSimpleName());
        }
    }
}
