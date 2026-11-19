package com.example.perfume_budget.tenancy;

import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.service.TenantRegistryService;
import com.example.perfume_budget.security.cors.TenantOriginPolicy;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * DOMAIN-02: the ONE rule that maps a browser host to a tenant, shared by {@link RequestTenantResolver} (HTTP and the
 * WebSocket handshake), {@code TenantOriginPolicy} (CORS) and {@code OAuth2RedirectValidator}, so they can never
 * disagree about which tenant a host belongs to.
 *
 * <ol>
 *   <li>a platform domain: no tenant;</li>
 *   <li>the base domain or a host under it: ONLY {@code <slug>.<base-domain>} with exactly one valid slug label, looked
 *       up by slug. A custom domain is never consulted here, so a (legacy, directly edited) custom domain under the
 *       base domain cannot capture another tenant's subdomain (the {@code ydom}/{@code acme.shopfi.test} collision);</li>
 *   <li>anything else: the tenant whose custom domain equals the host AND is VERIFIED
 *       ({@link TenantRegistryService#findByVerifiedCustomDomain}); PENDING/FAILED claims are ignored.</li>
 * </ol>
 * The tenant's status (ACTIVE, SUSPENDED, ...) is the caller's decision. Stateless: callers may create their own
 * instance over the same registry and properties.
 */
@Component
public class TenantHostLookup {

    private final TenantRegistryService registry;
    private final TenantResolutionProperties properties;

    public TenantHostLookup(TenantRegistryService registry, TenantResolutionProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    public Optional<Tenant> findByHost(String rawHost) {
        if (rawHost == null || rawHost.isBlank()) {
            return Optional.empty();
        }
        String host = rawHost.strip().toLowerCase(Locale.ROOT);
        if (properties.isPlatformDomain(host)) {
            return Optional.empty();
        }
        String base = baseDomain();
        if (!base.isEmpty() && (host.equals(base) || host.endsWith("." + base))) {
            String slug = TenantOriginPolicy.subdomainSlug(host, base);
            return slug == null ? Optional.empty() : registry.findBySlug(slug);
        }
        return registry.findByVerifiedCustomDomain(host);
    }

    private String baseDomain() {
        String base = properties.getBaseDomain();
        return base == null ? "" : base.strip().toLowerCase(Locale.ROOT);
    }
}
