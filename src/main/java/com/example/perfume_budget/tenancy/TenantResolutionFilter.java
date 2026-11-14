package com.example.perfume_budget.tenancy;

import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.security.cors.RequestPaths;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.service.TenantRegistryService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Optional;

/**
 * Resolves the tenant for every request and binds it to {@link TenantContext} before any
 * DB-touching filter (SiteVisitFilter) or authentication runs.
 *
 * <p>Topology: one frontend served at every tenant host, one backend at a single fixed API
 * host. The backend's own {@code Host} is therefore useless for identifying a tenant — the
 * signal is the browser-set <b>{@code Origin}</b> header (fallback {@code Referer}) = the
 * tenant frontend host, matched to a custom domain or {@code <slug>.base-domain}. The
 * {@code X-Tenant-ID} header is honoured only in non-prod (never when the {@code prod} profile is active) for testing.
 *
 * <p>Cross-tenant isolation ultimately rests on the JWT {@code tenant}-claim check in
 * {@link com.example.perfume_budget.filter.JWTFilter}, since the auth cookie is shared on the
 * single API host. Context is always cleared in {@code finally}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantResolutionFilter extends OncePerRequestFilter {

    private static final String PLATFORM_PATH_PREFIX = "/api/v1/platform";
    private static final String OAUTH2_PATH_PREFIX = "/oauth2";
    // EXPLICIT ALLOW-LIST of paths that may run with no tenant (TenantContext null): infra/docs,
    // static/error, and the payment webhook (resolves its tenant from the master route table by
    // token). "/oauth2" and platform paths are handled separately in doFilterInternal. Every
    // other request must carry a tenant signal or is rejected with 4xx.
    private static final String[] BYPASS_PREFIXES = {
            "/actuator", "/swagger-ui", "/v3/api-docs", "/swagger-ui.html",
            "/ws", "/login/oauth2", "/api/v1/webhook",
            "/error", "/favicon.ico"
    };

    private final TenantRegistryService tenantRegistryService;
    private final TenantResolutionProperties properties;
    private final Environment environment;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        // Decoded, lower-cased path (DOMAIN-01b S1): the raw URI would let %70latform skip platform scope.
        String path = RequestPaths.canonical(request);

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

            // OAuth2 authorize/callback run neutral here; the tenant is carried through the
            // authorization request (derived from redirect_uri) and restored by the cookie repo.
            if (path.startsWith(OAUTH2_PATH_PREFIX)) {
                filterChain.doFilter(request, response);
                return;
            }

            String originHost = resolveOriginHost(request);
            Optional<Tenant> resolved = resolveTenant(originHost, request);

            if (resolved.isEmpty()) {
                if (originHost != null) {
                    // A real browser origin that matches no tenant.
                    response.sendError(HttpServletResponse.SC_NOT_FOUND, "Unknown tenant");
                    return;
                }
                // No tenant signal: leave the context unbound. TenantRequiredFilter (next in the
                // chain) rejects it unless the path is on the neutral allow-list.
                filterChain.doFilter(request, response);
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

    private Optional<Tenant> resolveTenant(String originHost, HttpServletRequest request) {
        if (originHost != null && !properties.isPlatformDomain(originHost)) {
            Optional<Tenant> byDomain = tenantRegistryService.findByCustomDomain(originHost);
            if (byDomain.isPresent()) {
                return byDomain;
            }
            String subdomainSlug = extractSubdomainSlug(originHost);
            if (subdomainSlug != null) {
                Optional<Tenant> bySubdomain = tenantRegistryService.findBySlug(subdomainSlug);
                if (bySubdomain.isPresent()) {
                    return bySubdomain;
                }
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
    private String resolveOriginHost(HttpServletRequest request) {
        String host = hostFromUrl(request.getHeader("Origin"));
        if (host != null) {
            return host;
        }
        return hostFromUrl(request.getHeader("Referer"));
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

    /**
     * True for paths allowed to run with no tenant bound: the explicit allow-list
     * (BYPASS_PREFIXES), platform control-plane paths and OAuth2 endpoints. Used by
     * {@link TenantRequiredFilter} to decide which unresolved requests to reject.
     */
    static boolean isNeutralPath(String path) {
        return isBypass(path) || path.startsWith(PLATFORM_PATH_PREFIX) || path.startsWith(OAUTH2_PATH_PREFIX);
    }

    private static boolean isBypass(String path) {
        for (String prefix : BYPASS_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
