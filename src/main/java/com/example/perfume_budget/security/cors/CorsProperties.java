package com.example.perfume_budget.security.cors;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Tunables for CORS (prefix {@code cors}). Tenant storefront/admin origins are NOT listed here: they are derived
 * from the tenant registry (see {@link TenantOriginPolicy}). {@code allowed-origins} is only for non-tenant
 * origins (local dev, a shared console) and may be empty in production.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "cors")
public class CorsProperties {

    /** Extra exact origins ({@code scheme://host[:port]}) allowed on tenant paths. Never a wildcard. */
    private List<String> allowedOrigins = new ArrayList<>();
    /** Explicit methods; a wildcard is rejected at startup because credentials are allowed. */
    private List<String> allowedMethods = new ArrayList<>(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    /** Explicit request headers; a wildcard is rejected at startup. Cookies are the credential, so no Authorization. */
    private List<String> allowedHeaders = new ArrayList<>(List.of("Content-Type", "Accept", "If-Match", "If-None-Match"));
    /** Response headers a browser may read from a tenant/platform origin (API-01 AC0b). */
    private List<String> exposedHeaders = new ArrayList<>(List.of("ETag", "Retry-After"));
    /** Preflight cache lifetime in seconds. */
    private long maxAgeSeconds = 300;

    private TenantCache tenantCache = new TenantCache();

    @Getter
    @Setter
    public static class TenantCache {
        /** How long an "allowed" registry decision is reused: a suspension propagates within this many seconds. */
        private long ttlSeconds = 30;
        /** How long a "denied" decision is reused (short: a freshly activated tenant must not stay blocked). */
        private long negativeTtlSeconds = 10;
        private int maxEntries = 5000;
        private int negativeMaxEntries = 2000;
        /** Registry lookups allowed per second on cache misses (all keys together); beyond it the origin is denied. */
        private int maxLookupsPerSecond = 50;
    }
}
