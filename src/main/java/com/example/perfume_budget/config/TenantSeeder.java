package com.example.perfume_budget.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds a newly-provisioned tenant's default data. The caller MUST have an active
 * {@code TenantContext} set (so all writes land in that tenant's schema). Idempotent, so a
 * failed provisioning attempt can be retried safely. Invoked by the dev trigger now and by the
 * provisioning service in Stage 2.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantSeeder {

    private final ChartOfAccountsSeeder chartOfAccountsSeeder;
    private final FeatureFlagSeeder featureFlagSeeder;
    private final UomSeeder uomSeeder;
    private final DataSeeder dataSeeder;

    @Transactional
    public void seedTenantDefaults(String adminEmail, String adminPassword, String adminFullName) {
        chartOfAccountsSeeder.seed();
        featureFlagSeeder.seed();
        uomSeeder.seed();
        dataSeeder.seedAdmin(adminEmail, adminPassword, adminFullName);
        log.info("Seeded tenant defaults for admin {}", adminEmail);
    }
}
