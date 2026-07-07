package com.example.perfume_budget.tenancy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Runs all migrations at boot in the correct order — master, then the neutral
 * {@code tenant_template} (Hibernate's {@code validate} target), then every active
 * tenant schema. The EntityManagerFactory is forced to wait for this bean (see
 * {@link TenantFlywayEmfDependsOnConfig}) so validation never runs against an
 * unmigrated schema (migration-plan risk #3).
 */
@Component("tenantFlywayInitializer")
public class TenantFlywayInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(TenantFlywayInitializer.class);

    private final TenantSchemaMigrator migrator;

    public TenantFlywayInitializer(TenantSchemaMigrator migrator) {
        this.migrator = migrator;
    }

    @Override
    public void afterPropertiesSet() {
        log.info("Migrating master schema");
        migrator.migrateMaster();

        log.info("Migrating tenant_template schema");
        migrator.migrateTemplate();

        List<String> schemas = migrator.listActiveTenantSchemas();
        log.info("Migrating {} active tenant schema(s)", schemas.size());
        for (String schema : schemas) {
            log.info("Migrating tenant schema {}", schema);
            migrator.migrateTenant(schema);
        }
    }
}
