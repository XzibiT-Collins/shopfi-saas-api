package com.example.perfume_budget.config.ws;

import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.service.TenantRegistryService;
import com.example.perfume_budget.security.CustomUserDetailsService;
import com.example.perfume_budget.tenancy.TenantContext;
import com.example.perfume_budget.tenancy.TenantIdentity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * SEC-03e F5: periodically re-checks every authenticated STOMP session and closes the ones that are no longer valid:
 * the CONNECT token has expired, the tenant is no longer ACTIVE, or the user is gone or disabled. A session therefore
 * outlives such an event by at most {@code shopfi.ws.revalidate-interval-ms} (15 s by default). A database failure
 * during a check leaves the sessions open (the next pass retries); only class names are logged.
 */
@Slf4j
@Component
public class StompSessionRevalidator {

    private final StompSessionRegistry registry;
    private final TenantRegistryService tenantRegistry;
    private final CustomUserDetailsService userDetailsService;
    private final LongSupplier clock;

    @Autowired
    public StompSessionRevalidator(StompSessionRegistry registry, TenantRegistryService tenantRegistry,
                                   CustomUserDetailsService userDetailsService) {
        this(registry, tenantRegistry, userDetailsService, System::currentTimeMillis);
    }

    StompSessionRevalidator(StompSessionRegistry registry, TenantRegistryService tenantRegistry,
                            CustomUserDetailsService userDetailsService, LongSupplier clock) {
        this.registry = registry;
        this.tenantRegistry = tenantRegistry;
        this.userDetailsService = userDetailsService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${shopfi.ws.revalidate-interval-ms:15000}")
    public void revalidate() {
        long now = clock.getAsLong();
        // expiry first: no lookups needed
        registry.closeWhere(a -> a.expiresAtMillis() <= now, "Session expired");

        Map<String, Set<String>> usersByTenant = new HashMap<>();
        registry.snapshot().values().forEach(a ->
                usersByTenant.computeIfAbsent(a.tenant(), t -> new HashSet<>()).add(a.email()));
        usersByTenant.forEach((slug, emails) -> {
            try {
                Tenant tenant = tenantRegistry.findBySlug(slug).orElse(null);
                if (tenant == null || tenant.getStatus() != TenantStatus.ACTIVE) {
                    registry.closeTenant(slug, "Tenant unavailable");
                    return;
                }
                TenantIdentity previous = TenantContext.get();
                TenantContext.set(TenantIdentity.tenant(slug, tenant.getSchemaName()));
                try {
                    for (String email : emails) {
                        if (!enabled(email)) {
                            registry.closeUser(slug, email, "Account no longer active");
                        }
                    }
                } finally {
                    if (previous != null) {
                        TenantContext.set(previous);
                    } else {
                        TenantContext.clear();
                    }
                }
            } catch (RuntimeException e) {
                log.warn("STOMP session revalidation failed for one tenant: {}", e.getClass().getSimpleName());
            }
        });
    }

    private boolean enabled(String email) {
        try {
            UserDetails user = userDetailsService.loadUserByUsername(email);
            return user.isEnabled();
        } catch (AuthenticationException e) {
            return false;
        }
    }
}
