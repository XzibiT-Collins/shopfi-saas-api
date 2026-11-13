package com.example.perfume_budget.tenancy;

import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Hooks schema multi-tenancy into the Spring-autoconfigured EntityManagerFactory
 * (rather than hand-building the EMF) by registering the custom connection provider
 * and tenant resolver as Hibernate properties. Providing a
 * {@code MULTI_TENANT_CONNECTION_PROVIDER} is what switches Hibernate 6 into
 * schema-per-tenant mode.
 */
@Configuration
public class MultiTenancyJpaConfig implements HibernatePropertiesCustomizer {

    private final SchemaMultiTenantConnectionProvider connectionProvider;
    private final TenantIdentifierResolver tenantIdentifierResolver;

    public MultiTenancyJpaConfig(SchemaMultiTenantConnectionProvider connectionProvider,
                                 TenantIdentifierResolver tenantIdentifierResolver) {
        this.connectionProvider = connectionProvider;
        this.tenantIdentifierResolver = tenantIdentifierResolver;
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.MULTI_TENANT_CONNECTION_PROVIDER, connectionProvider);
        hibernateProperties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, tenantIdentifierResolver);
    }
}
