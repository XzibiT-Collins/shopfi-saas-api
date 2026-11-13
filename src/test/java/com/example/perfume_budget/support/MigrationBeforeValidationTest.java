package com.example.perfume_budget.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TENANT-02 AC3: Flyway migration runs BEFORE Hibernate builds/validates the EntityManagerFactory
 * (EntityManagerFactoryDependsOnPostProcessor in TenantFlywayEmfDependsOnConfig).
 */
class MigrationBeforeValidationTest extends AbstractPostgresIntegrationTest {

    @Autowired ConfigurableApplicationContext context;

    @Test
    void entityManagerFactoryDependsOnTenantFlywayInitializer() {
        DefaultListableBeanFactory bf = (DefaultListableBeanFactory) context.getBeanFactory();
        assertThat(Arrays.asList(bf.getDependenciesForBean("entityManagerFactory")))
                .contains("tenantFlywayInitializer");
    }

    @Test
    void schemasWereEmptyAtBootAndMigratedByTheTimeHibernateStartedValidating() {
        // Boot began on an empty database (all app schemas dropped), and validate=true still passed...
        assertThat(AbstractPostgresIntegrationTest.EMPTY_DB_AT_FIRST_BOOT.get()).isTrue();
        // ...because master + tenant_template tables already existed when the EMF started to build.
        assertThat(AbstractPostgresIntegrationTest.BoundaryRecorder.MIGRATED_BEFORE_EMF.get()).isTrue();
    }
}
