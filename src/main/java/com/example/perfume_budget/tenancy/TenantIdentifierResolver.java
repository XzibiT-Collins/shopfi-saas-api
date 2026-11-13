package com.example.perfume_budget.tenancy;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

/**
 * Tells Hibernate which schema the current unit of work belongs to by reading the
 * thread-bound {@link TenantContext}. Falls back to the neutral schema when no
 * tenant is set (boot-time validation, platform-scoped requests).
 */
@Component
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<String> {

    @Override
    public String resolveCurrentTenantIdentifier() {
        return TenantContext.getSchemaOrDefault();
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }
}
