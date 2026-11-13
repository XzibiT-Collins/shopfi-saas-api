package com.example.perfume_budget.support;

import com.example.perfume_budget.config.TenantSeeder;
import com.example.perfume_budget.platform.enums.TenantPlan;
import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.repository.TenantRepository;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantIdentity;
import com.example.perfume_budget.tenancy.TenantSchemaMigrator;

import java.util.function.Supplier;

/**
 * Reusable fixture for TENANT-03/04/05: registers tenants in {@code master.tenants}, migrates their
 * schema with {@link TenantSchemaMigrator} and seeds defaults (admin, chart of accounts, feature flags,
 * UoM) with {@link TenantSeeder} under the tenant's {@link TenantContext}. Idempotent, so any test
 * class may call {@link #acme()} / {@link #bravo()} and share the cached context.
 * Autowire it from any test extending {@link AbstractPostgresIntegrationTest}.
 */
public class TestTenants {

    public static final String ADMIN_PASSWORD = "Passw0rd!test";

    private final TenantRepository tenantRepository;
    private final TenantSchemaMigrator migrator;
    private final TenantSeeder seeder;

    public TestTenants(TenantRepository tenantRepository, TenantSchemaMigrator migrator, TenantSeeder seeder) {
        this.tenantRepository = tenantRepository;
        this.migrator = migrator;
        this.seeder = seeder;
    }

    public TestTenant acme() {
        return ensureTenant("acme");
    }

    public TestTenant bravo() {
        return ensureTenant("bravo");
    }

    /** Creates (if missing) an ACTIVE tenant with schema {@code tenant_<slug>} and admin {@code admin@<slug>.test}. */
    public synchronized TestTenant ensureTenant(String slug) {
        String schema = "tenant_" + slug.replace('-', '_');
        String email = "admin@" + slug + ".test";
        TestTenant tenant = new TestTenant(slug, schema, email, ADMIN_PASSWORD);

        if (tenantRepository.existsBySlug(slug)) {
            return tenant;
        }
        Tenant row = tenantRepository.save(Tenant.builder()
                .slug(slug)
                .displayName(slug)
                .schemaName(schema)
                .status(TenantStatus.PROVISIONING)
                .plan(TenantPlan.FREE)
                .ownerEmail(email)
                .build());

        migrator.migrateTenant(schema);
        runAs(tenant, () -> {
            seeder.seedTenantDefaults(email, ADMIN_PASSWORD, slug + " admin");
            return null;
        });

        row.setStatus(TenantStatus.ACTIVE);
        tenantRepository.save(row);
        return tenant;
    }

    /** Runs {@code action} with the tenant bound to {@link TenantContext}, always clearing it afterwards. */
    public <T> T runAs(TestTenant tenant, Supplier<T> action) {
        TenantContext.set(TenantIdentity.tenant(tenant.slug(), tenant.schemaName()));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    public void runAs(TestTenant tenant, Runnable action) {
        runAs(tenant, () -> {
            action.run();
            return null;
        });
    }
}
