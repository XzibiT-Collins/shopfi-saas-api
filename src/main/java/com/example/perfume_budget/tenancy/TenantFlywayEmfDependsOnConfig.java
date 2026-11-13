package com.example.perfume_budget.tenancy;

import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Forces the autoconfigured EntityManagerFactory to depend on
 * {@code tenantFlywayInitializer}, guaranteeing master + tenant_template + tenant
 * schemas are migrated before Hibernate builds/validates the EMF.
 */
@Configuration
public class TenantFlywayEmfDependsOnConfig {

    @Bean
    public static EntityManagerFactoryDependsOnPostProcessor tenantFlywayEmfDependsOnPostProcessor() {
        return new EntityManagerFactoryDependsOnPostProcessor("tenantFlywayInitializer");
    }
}
