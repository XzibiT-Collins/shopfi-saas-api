package com.example.perfume_budget.tenancy;

import com.example.perfume_budget.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** TENANT-11 AC3 (N2): the fail-safe guard is a bean in the real application context. */
class TenantHeaderResolutionGuardBeanTest extends AbstractPostgresIntegrationTest {

    @Autowired ApplicationContext ctx;

    @Test
    void guardIsRegisteredInRealContext() {
        assertThat(ctx.getBeansOfType(TenantHeaderResolutionGuard.class)).hasSize(1);
    }
}
