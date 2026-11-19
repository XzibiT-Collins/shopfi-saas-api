package com.example.perfume_budget.security.oauth2;

import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.service.TenantRegistryService;
import com.example.perfume_budget.tenancy.TenantHostLookup;
import com.example.perfume_budget.tenancy.TenantResolutionProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Optional;

/**
 * Single source of truth for the OAuth2 open-redirect gate.
 *
 * <p>A login {@code redirect_uri} is trusted only if its host belongs to a known tenant
 * (a VERIFIED custom domain or {@code <slug>.base-domain}, through the shared {@link TenantHostLookup}, DOMAIN-02),
 * or, in non-prod, is an allow-listed dev host
 * paired with a valid {@code X-Tenant-ID}. Used both when starting the flow (to pick the
 * tenant) and again at the redirect point (defense in depth), so an attacker-supplied redirect
 * can never be followed.
 */
@Component
@RequiredArgsConstructor
public class OAuth2RedirectValidator {

    private final TenantRegistryService tenantRegistryService;
    private final TenantResolutionProperties properties;
    private final TenantHostLookup hostLookup;

    /** Resolve the tenant slug a redirect_uri is allowed to belong to, if any. */
    public Optional<String> resolveAllowedTenant(String redirectUri, HttpServletRequest request) {
        String host = hostFromUrl(redirectUri);
        if (host == null) {
            return Optional.empty();
        }

        Optional<Tenant> byHost = hostLookup.findByHost(host);
        if (byHost.isPresent()) {
            return Optional.of(byHost.get().getSlug());
        }

        if (isDevHost(host)) {
            String headerSlug = request.getHeader(properties.getHeaderName());
            if (headerSlug != null && !headerSlug.isBlank()) {
                String slug = headerSlug.trim().toLowerCase();
                if (tenantRegistryService.findBySlug(slug).isPresent()) {
                    return Optional.of(slug);
                }
            }
        }
        return Optional.empty();
    }

    /** True if {@code redirectUri}'s host is a legitimate destination for the given tenant. */
    public boolean isAllowedForTenant(String redirectUri, String slug) {
        String host = hostFromUrl(redirectUri);
        if (host == null || slug == null) {
            return false;
        }
        Optional<Tenant> tenant = tenantRegistryService.findBySlug(slug);
        if (tenant.isPresent()) {
            // DOMAIN-02: a PENDING/FAILED custom domain is not a legitimate destination
            Optional<String> customDomain = tenant.get().verifiedCustomDomain();
            if (customDomain.isPresent() && customDomain.get().equalsIgnoreCase(host)) {
                return true;
            }
            if (host.equalsIgnoreCase(subdomainHost(slug))) {
                return true;
            }
        }
        return isDevHost(host);
    }

    private boolean isDevHost(String host) {
        return properties.getHeaderResolution().isEnabled()
                && properties.getOauth2().getDevAllowedRedirectHosts().contains(host);
    }

    private String subdomainHost(String slug) {
        String base = properties.getBaseDomain();
        return (base == null || base.isBlank()) ? null : slug + "." + base.toLowerCase();
    }

    private String hostFromUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(value).getHost();
            return host != null ? host.toLowerCase() : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
