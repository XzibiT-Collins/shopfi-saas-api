package com.example.perfume_budget.tenancy;

import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.service.TenantRegistryService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Resolves the tenant for every request and binds it to {@link TenantContext} before
 * any DB-touching filter (SiteVisitFilter) or authentication runs. This solves the
 * login chicken-and-egg problem: the tenant is known from the host/header before the
 * user is authenticated.
 *
 * <p>Precedence: custom domain → subdomain → dev header → apex/platform host → 404.
 * The header path is gated to non-prod. Infrastructure paths (docs, actuator, ws,
 * oauth2) bypass resolution and run neutral. Context is always cleared in {@code finally}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantResolutionFilter extends OncePerRequestFilter {

    private static final String PLATFORM_PATH_PREFIX = "/api/v1/platform";
    private static final String[] BYPASS_PREFIXES = {
            "/actuator", "/swagger-ui", "/v3/api-docs", "/swagger-ui.html",
            "/ws", "/test", "/oauth2", "/login/oauth2"
    };

    private final TenantRegistryService tenantRegistryService;
    private final TenantResolutionProperties properties;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();

        // Infrastructure/neutral paths: run without a tenant context.
        if (isBypass(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            // Platform control-plane paths are always platform-scoped.
            if (path.startsWith(PLATFORM_PATH_PREFIX)) {
                TenantContext.set(TenantIdentity.platform());
                filterChain.doFilter(request, response);
                return;
            }

            String host = normalizeHost(request.getServerName());
            Optional<Tenant> resolved = resolveTenant(host, request);

            if (resolved.isEmpty()) {
                // No tenant matched. Apex/platform hosts serve the platform scope; anything
                // else targeting a tenant surface is an unknown tenant.
                if (properties.isPlatformDomain(host) || host.equalsIgnoreCase(properties.getBaseDomain())) {
                    TenantContext.set(TenantIdentity.platform());
                    filterChain.doFilter(request, response);
                    return;
                }
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Unknown tenant");
                return;
            }

            Tenant tenant = resolved.get();
            if (tenant.getStatus() == TenantStatus.SUSPENDED) {
                response.sendError(423, "Tenant suspended");
                return;
            }
            if (tenant.getStatus() != TenantStatus.ACTIVE) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Tenant not available");
                return;
            }

            TenantContext.set(TenantIdentity.tenant(tenant.getSlug(), tenant.getSchemaName()));
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private Optional<Tenant> resolveTenant(String host, HttpServletRequest request) {
        // 1 + 2: host-based resolution only when the host is not itself a platform apex.
        if (!properties.isPlatformDomain(host)) {
            Optional<Tenant> byDomain = tenantRegistryService.findByCustomDomain(host);
            if (byDomain.isPresent()) {
                return byDomain;
            }
            String subdomainSlug = extractSubdomainSlug(host);
            if (subdomainSlug != null) {
                Optional<Tenant> bySubdomain = tenantRegistryService.findBySlug(subdomainSlug);
                if (bySubdomain.isPresent()) {
                    return bySubdomain;
                }
            }
        }

        // 3: dev/test header, non-prod only.
        if (properties.getHeaderResolution().isEnabled()) {
            String headerSlug = request.getHeader(properties.getHeaderName());
            if (headerSlug != null && !headerSlug.isBlank()) {
                return tenantRegistryService.findBySlug(headerSlug.trim().toLowerCase());
            }
        }

        return Optional.empty();
    }

    private String extractSubdomainSlug(String host) {
        String base = properties.getBaseDomain();
        if (base == null || base.isBlank()) {
            return null;
        }
        String suffix = "." + base.toLowerCase();
        if (host.endsWith(suffix) && host.length() > suffix.length()) {
            return host.substring(0, host.length() - suffix.length());
        }
        return null;
    }

    private String normalizeHost(String serverName) {
        if (serverName == null) {
            return "";
        }
        String host = serverName.toLowerCase();
        int colon = host.indexOf(':');
        return colon > -1 ? host.substring(0, colon) : host;
    }

    private boolean isBypass(String path) {
        for (String prefix : BYPASS_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
