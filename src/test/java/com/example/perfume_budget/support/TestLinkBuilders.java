package com.example.perfume_budget.support;

import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.service.OnboardingProperties;
import com.example.perfume_budget.platform.service.TenantRegistryService;
import com.example.perfume_budget.tenancy.TenantLinkBuilder;
import com.example.perfume_budget.tenancy.TenantResolutionProperties;

import java.util.Optional;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/** Unit-test factory for a {@link TenantLinkBuilder} over a fake registry: acme (subdomain) and shop (custom domain). */
public final class TestLinkBuilders {

    private TestLinkBuilders() {
    }

    public static TenantLinkBuilder linkBuilder(String baseDomain, String fallbackUrl) {
        TenantRegistryService registry = mock(TenantRegistryService.class);
        lenient().when(registry.findBySlug("acme")).thenReturn(Optional.of(Tenant.builder().slug("acme").build()));
        lenient().when(registry.findBySlug("shop")).thenReturn(Optional.of(
                Tenant.builder().slug("shop").customDomain("Shop.Example.COM").build()));
        TenantResolutionProperties props = new TenantResolutionProperties();
        props.setBaseDomain(baseDomain);
        return new TenantLinkBuilder(registry, props, new OnboardingProperties(), fallbackUrl, "/reset-password", "/login");
    }
}
