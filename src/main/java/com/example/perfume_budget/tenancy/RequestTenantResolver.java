package com.example.perfume_budget.tenancy;

import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.service.TenantRegistryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Optional;

/**
 * The single set of rules that turns a request into a tenant (SEC-03c): the browser-set {@code Origin} host (fallback
 * {@code Referer}) matched to a custom domain or {@code <slug>.base-domain}, and the {@code X-Tenant-ID} header in
 * non-prod only. Used by {@link TenantResolutionFilter} for HTTP and by the WebSocket handshake, so the two can never
 * disagree about which tenant a browser page belongs to.
 *
 * <p>The backend's own {@code Host} is not a tenant signal: every tenant frontend talks to one fixed API host.
 */
@Component
public class RequestTenantResolver {

    /** What the request resolved to. */
    public enum Outcome {
        /** An ACTIVE tenant; {@link Resolution#identity()} is set. */
        RESOLVED,
        /** No Origin/Referer and no (allowed) dev header: no tenant signal at all. */
        NO_SIGNAL,
        /** A browser origin (or dev header) that matches no tenant. */
        UNKNOWN,
        /** The tenant exists but is SUSPENDED. */
        SUSPENDED,
        /** The tenant exists but is neither ACTIVE nor SUSPENDED (e.g. still provisioning). */
        UNAVAILABLE
    }

    public record Resolution(Outcome outcome, TenantIdentity identity) {
        static Resolution of(Outcome outcome) {
            return new Resolution(outcome, null);
        }
    }

    private final TenantRegistryService tenantRegistryService;
    private final TenantResolutionProperties properties;
    private final Environment environment;
    private final TenantHostLookup hostLookup;

    @Autowired
    public RequestTenantResolver(TenantRegistryService tenantRegistryService, TenantResolutionProperties properties,
                                 Environment environment) {
        this.tenantRegistryService = tenantRegistryService;
        this.properties = properties;
        this.environment = environment;
        this.hostLookup = new TenantHostLookup(tenantRegistryService, properties);
    }

    public Resolution resolve(HttpServletRequest request) {
        String originHost = resolveOriginHost(request);
        Optional<Tenant> resolved = resolveTenant(originHost, request);
        if (resolved.isEmpty()) {
            // A real browser origin that matches no tenant is UNKNOWN; no signal at all is left to the caller.
            return Resolution.of(originHost != null ? Outcome.UNKNOWN : Outcome.NO_SIGNAL);
        }
        Tenant tenant = resolved.get();
        if (tenant.getStatus() == TenantStatus.SUSPENDED) {
            return Resolution.of(Outcome.SUSPENDED);
        }
        if (tenant.getStatus() != TenantStatus.ACTIVE) {
            return Resolution.of(Outcome.UNAVAILABLE);
        }
        return new Resolution(Outcome.RESOLVED, TenantIdentity.tenant(tenant.getId(), tenant.getSlug(), tenant.getSchemaName()));
    }

    private Optional<Tenant> resolveTenant(String originHost, HttpServletRequest request) {
        if (originHost != null) {
            // DOMAIN-02: one shared host rule (VERIFIED custom domains only; hosts under the base domain by slug only)
            Optional<Tenant> byHost = hostLookup.findByHost(originHost);
            if (byHost.isPresent()) {
                return byHost;
            }
        }

        // Dev/test header, non-prod only.
        if (properties.getHeaderResolution().isEnabled() && !environment.acceptsProfiles(Profiles.of("prod"))) {
            String headerSlug = request.getHeader(properties.getHeaderName());
            if (headerSlug != null && !headerSlug.isBlank()) {
                return tenantRegistryService.findBySlug(headerSlug.trim().toLowerCase());
            }
        }

        return Optional.empty();
    }

    /** Host from the {@code Origin} header, falling back to {@code Referer}. */
    private static String resolveOriginHost(HttpServletRequest request) {
        String host = hostFromUrl(request.getHeader("Origin"));
        if (host != null) {
            return host;
        }
        return hostFromUrl(request.getHeader("Referer"));
    }

    static String hostFromUrl(String value) {
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
