package com.example.perfume_budget.tenancy;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Fail-safe: the {@code X-Tenant-ID} header lets a caller pick any tenant, so it may only be enabled
 * when a {@code dev} or test ({@code test}/{@code pgtest}) profile is explicitly active. Any other
 * combination (including no profile at all) aborts startup instead of silently serving a
 * header-steerable tenant.
 */
@Component
@RequiredArgsConstructor
public class TenantHeaderResolutionGuard implements InitializingBean {

    private final TenantResolutionProperties properties;
    private final Environment environment;

    @Override
    public void afterPropertiesSet() {
        if (properties.getHeaderResolution().isEnabled()
                && !environment.acceptsProfiles(Profiles.of("dev", "test", "pgtest"))) {
            throw new IllegalStateException(
                    "tenant.header-resolution.enabled=true is only allowed with the dev, test or pgtest profile; "
                            + "refusing to start with an unsafe tenant resolution configuration.");
        }
    }
}
