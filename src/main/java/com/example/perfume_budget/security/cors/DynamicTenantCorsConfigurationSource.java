package com.example.perfume_budget.security.cors;

import com.example.perfume_budget.security.cors.TenantOriginPolicy.ParsedOrigin;
import com.example.perfume_budget.tenancy.TenantResolutionProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Per-request CORS configuration (DOMAIN-01). Three zones, each with its own rules; the matched origin is echoed
 * exactly (never {@code *}), credentials are only ever enabled together with an exact origin, and a rejected
 * origin gets Spring's standard 403 with no {@code Access-Control-Allow-*} headers.
 *
 * <ul>
 *   <li><b>Platform control plane</b> ({@code /api/v1/platform/**} except onboarding): only
 *       {@code platform.cors.allowed-origins}, with credentials. Tenant origins and {@code cors.allowed-origins}
 *       are never accepted here (PLAT-03 AC3).</li>
 *   <li><b>Public onboarding</b> ({@code /api/v1/platform/onboarding/**}): the platform console origins plus the
 *       apex hosts in {@code tenant.platform-domains}, WITHOUT credentials (the wizard uses
 *       {@code credentials: 'omit'}).</li>
 *   <li><b>Everything else</b> (tenant API, WebSocket, ...): {@link TenantOriginPolicy} (active tenant
 *       subdomain/custom domain, or the static list), with credentials, exposing ETag and Retry-After.</li>
 * </ul>
 * Requests without an Origin are not CORS requests and get no configuration. Several Origin headers are rejected.
 */
@Component
public class DynamicTenantCorsConfigurationSource implements CorsConfigurationSource {

    static final String PLATFORM_PREFIX = "/api/v1/platform";
    static final String ONBOARDING_PREFIX = "/api/v1/platform/onboarding";

    private final TenantOriginPolicy policy;
    private final CorsProperties props;
    private final TenantResolutionProperties tenantProps;
    private final Set<String> platformOrigins;
    private final boolean prod;
    private final List<String> tenantHeaders;

    public DynamicTenantCorsConfigurationSource(TenantOriginPolicy policy, CorsProperties props,
                                                PlatformCorsProperties platformProps,
                                                TenantResolutionProperties tenantProps, Environment environment) {
        this.policy = policy;
        this.props = props;
        this.tenantProps = tenantProps;
        this.prod = environment.acceptsProfiles(Profiles.of("prod"));
        this.platformOrigins = TenantOriginPolicy.normaliseOrigins(platformProps.getAllowedOrigins(), "platform.cors.allowed-origins");
        // The dev tenant header is only honoured outside prod, so only then may a browser send it cross-origin.
        java.util.ArrayList<String> headers = new java.util.ArrayList<>(props.getAllowedHeaders());
        if (!prod && tenantProps.getHeaderResolution().isEnabled()
                && headers.stream().noneMatch(h -> h.equalsIgnoreCase(tenantProps.getHeaderName()))) {
            headers.add(tenantProps.getHeaderName());
        }
        this.tenantHeaders = List.copyOf(headers);
    }

    @Override
    public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
        java.util.Enumeration<String> values = request.getHeaders("Origin");
        if (values == null || !values.hasMoreElements()) {
            return null;
        }
        String origin = values.nextElement();
        if (values.hasMoreElements()) {
            return deny(); // several Origin headers: ambiguous, same rule as the theme controller
        }
        String path = request.getRequestURI();
        if (path == null) {
            return deny();
        }
        if (isUnder(path, ONBOARDING_PREFIX)) {
            return onboarding(origin);
        }
        if (path.startsWith(PLATFORM_PREFIX)) {
            return platform(origin);
        }
        return tenant(origin);
    }

    private CorsConfiguration platform(String origin) {
        ParsedOrigin p = TenantOriginPolicy.parse(origin);
        if (p == null || !platformOrigins.contains(p.canonical())) {
            return deny();
        }
        return allow(origin, props.getAllowedMethods(), props.getAllowedHeaders(), true);
    }

    private CorsConfiguration onboarding(String origin) {
        ParsedOrigin p = TenantOriginPolicy.parse(origin);
        if (p == null) {
            return deny();
        }
        boolean ok = platformOrigins.contains(p.canonical())
                || (tenantProps.isPlatformDomain(p.host()) && policy.schemeAndPortAllowed(p));
        return ok ? allow(origin, List.of("GET", "POST", "OPTIONS"), List.of("Content-Type", "Accept"), false) : deny();
    }

    private CorsConfiguration tenant(String origin) {
        return policy.isAllowed(origin) ? allow(origin, props.getAllowedMethods(), tenantHeaders, true) : deny();
    }

    private CorsConfiguration allow(String origin, List<String> methods, List<String> headers, boolean credentials) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(List.of(origin));
        c.setAllowedMethods(methods);
        c.setAllowedHeaders(headers);
        c.setExposedHeaders(props.getExposedHeaders());
        c.setAllowCredentials(credentials);
        c.setMaxAge(props.getMaxAgeSeconds());
        return c;
    }

    /** A configuration that allows nothing: Spring answers a CORS request with 403 and no CORS headers. */
    private static CorsConfiguration deny() {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(Collections.emptyList());
        return c;
    }

    private static boolean isUnder(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
