package com.example.perfume_budget.security.cors;

import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.service.TenantRegistryService;
import com.example.perfume_budget.platform.service.TokenBucketRateLimiter;
import com.example.perfume_budget.tenancy.TenantResolutionProperties;
import com.example.perfume_budget.tenancy.TenantSlugs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single decision function for "may this browser Origin talk to a tenant API / WebSocket?" (DOMAIN-01).
 *
 * <p>An origin is allowed iff it is, after strict parsing:
 * <ol>
 *   <li>(c) in the static {@code cors.allowed-origins} list (exact, normalised; a wildcard fails startup), or</li>
 *   <li>(a) {@code <scheme>://<slug>.<tenant.base-domain>} with exactly ONE label under the base domain, a slug
 *       that passes {@link TenantSlugs} (syntax and reserved names) and an ACTIVE tenant with that slug, or</li>
 *   <li>(b) {@code <scheme>://<customDomain>} of an ACTIVE tenant.</li>
 * </ol>
 * Non-prod profiles accept http and an explicit port for (a)/(b); the prod profile accepts https on the default
 * port only. Uppercase hosts are lowercased (the resolution filter does the same); a trailing dot, userinfo, a
 * path, unicode or the literal {@code null} are rejected.
 *
 * <p><b>Cost control.</b> Syntax is checked first, so garbage never reaches the registry. Registry results are
 * cached in two bounded LRU maps (allowed decisions and denied decisions are separate, so a flood of unknown
 * slugs cannot evict the allowed entries) with a short TTL. Cache misses additionally share one token bucket;
 * when it is empty the origin is denied (fail closed, nothing cached). A tenant suspended in the registry stops
 * being allowed within {@code cors.tenant-cache.ttl-seconds} (default 30); call {@link #invalidateAll()} for an
 * immediate effect.
 */
@Slf4j
@Component
public class TenantOriginPolicy {

    /** scheme://host[:port], nothing else (no userinfo, path, query, whitespace or non-ASCII). */
    private static final Pattern ORIGIN = Pattern.compile(
            "^(https?)://([A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?)(?::([1-9][0-9]{0,4}))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern IPV4 = Pattern.compile("^[0-9.]+$");

    /** A syntactically valid origin; {@code host} is lowercase. */
    public record ParsedOrigin(String scheme, String host, int port) {
        /** {@code scheme://host[:port]} with the port omitted when absent. */
        public String canonical() {
            return scheme + "://" + host + (port > 0 ? ":" + port : "");
        }
    }

    private final TenantRegistryService registry;
    private final TenantResolutionProperties tenantProps;
    private final boolean prod;
    private final Set<String> staticOrigins;
    private final Cache allowedCache;
    private final Cache deniedCache;
    private final long ttlNanos;
    private final long negativeTtlNanos;
    private final TokenBucketRateLimiter missLimiter;
    private final LongSupplier clock;

    @Autowired
    public TenantOriginPolicy(TenantRegistryService registry, TenantResolutionProperties tenantProps,
                              CorsProperties corsProps, Environment environment) {
        this(registry, tenantProps, corsProps, environment.acceptsProfiles(Profiles.of("prod")), System::nanoTime);
    }

    TenantOriginPolicy(TenantRegistryService registry, TenantResolutionProperties tenantProps,
                       CorsProperties corsProps, boolean prod, LongSupplier clock) {
        this.registry = registry;
        this.tenantProps = tenantProps;
        this.prod = prod;
        this.clock = clock;
        CorsProperties.TenantCache c = corsProps.getTenantCache();
        this.ttlNanos = Duration.ofSeconds(Math.max(1, c.getTtlSeconds())).toNanos();
        this.negativeTtlNanos = Duration.ofSeconds(Math.max(1, c.getNegativeTtlSeconds())).toNanos();
        this.allowedCache = new Cache(Math.max(1, c.getMaxEntries()));
        this.deniedCache = new Cache(Math.max(1, c.getNegativeMaxEntries()));
        this.missLimiter = new TokenBucketRateLimiter(Math.max(1, c.getMaxLookupsPerSecond()),
                Duration.ofSeconds(1), 4, clock);
        this.staticOrigins = normaliseConfigured(corsProps.getAllowedOrigins(), "cors.allowed-origins");
        assertNoWildcard(corsProps.getAllowedMethods(), "cors.allowed-methods");
        assertNoWildcard(corsProps.getAllowedHeaders(), "cors.allowed-headers");
        assertNoWildcard(corsProps.getExposedHeaders(), "cors.exposed-headers");
    }

    /** Parses an Origin value strictly; null when it is not exactly {@code http(s)://host[:port]}. */
    public static ParsedOrigin parse(String origin) {
        if (origin == null || origin.length() > 300) {
            return null;
        }
        Matcher m = ORIGIN.matcher(origin);
        if (!m.matches()) {
            return null;
        }
        String host = m.group(2).toLowerCase(Locale.ROOT);
        for (String label : host.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) {
                return null;
            }
        }
        int port = 0;
        if (m.group(3) != null) {
            port = Integer.parseInt(m.group(3));
            if (port > 65535) {
                return null;
            }
        }
        return new ParsedOrigin(m.group(1).toLowerCase(Locale.ROOT), host, port);
    }

    /** The slug in {@code <slug>.<base>} when the host has exactly one label under the base domain, else null. */
    public static String subdomainSlug(String host, String baseDomain) {
        if (host == null || baseDomain == null || baseDomain.isBlank()) {
            return null;
        }
        String suffix = "." + baseDomain.strip().toLowerCase(Locale.ROOT);
        if (!host.endsWith(suffix) || host.length() == suffix.length()) {
            return null;
        }
        String label = host.substring(0, host.length() - suffix.length());
        if (label.indexOf('.') >= 0) {
            return null;
        }
        try {
            return TenantSlugs.normalize(label).equals(label) ? label : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** True if the origin is in the static list or belongs to an ACTIVE tenant (rules (a), (b), (c)). */
    public boolean isAllowed(String origin) {
        ParsedOrigin p = parse(origin);
        if (p == null) {
            return false;
        }
        if (staticOrigins.contains(p.canonical())) {
            return true;
        }
        return isActiveTenantOrigin(p);
    }

    /** True if the origin is an ACTIVE tenant's subdomain or custom domain (no static list). */
    public boolean isTenantOrigin(String origin) {
        ParsedOrigin p = parse(origin);
        return p != null && isActiveTenantOrigin(p);
    }

    /** Whether the origin passes the scheme/port rules for tenant hosts (https and default port only in prod). */
    public boolean schemeAndPortAllowed(ParsedOrigin p) {
        return !prod || ("https".equals(p.scheme()) && p.port() == 0);
    }

    private boolean isActiveTenantOrigin(ParsedOrigin p) {
        if (!schemeAndPortAllowed(p)) {
            return false;
        }
        String host = p.host();
        String base = tenantProps.getBaseDomain() == null ? "" : tenantProps.getBaseDomain().strip().toLowerCase(Locale.ROOT);
        if (!base.isEmpty() && (host.equals(base) || host.endsWith("." + base))) {
            String slug = subdomainSlug(host, base);
            return slug != null && cachedActive("s:" + slug, () -> registry.findBySlug(slug));
        }
        if (tenantProps.isPlatformDomain(host) || IPV4.matcher(host).matches() || !host.contains(".")) {
            return false;
        }
        return cachedActive("d:" + host, () -> registry.findByCustomDomain(host));
    }

    private boolean cachedActive(String key, java.util.function.Supplier<java.util.Optional<Tenant>> lookup) {
        long now = clock.getAsLong();
        Boolean hit = allowedCache.get(key, now);
        if (hit != null) {
            return true;
        }
        if (deniedCache.get(key, now) != null) {
            return false;
        }
        if (missLimiter.tryAcquire("registry") != 0) {
            log.warn("CORS registry lookups are rate limited; denying origin without caching");
            return false;
        }
        boolean active;
        try {
            active = lookup.get().map(t -> t.getStatus() == TenantStatus.ACTIVE).orElse(false);
        } catch (RuntimeException e) {
            log.warn("CORS tenant lookup failed; denying origin: {}", e.toString());
            return false;
        }
        if (active) {
            allowedCache.put(key, now + ttlNanos);
        } else {
            deniedCache.put(key, now + negativeTtlNanos);
        }
        return active;
    }

    /** Drops every cached decision (call after a tenant is suspended, activated or its domain changes). */
    public void invalidateAll() {
        allowedCache.clear();
        deniedCache.clear();
    }

    private static Set<String> normaliseConfigured(java.util.List<String> configured, String property) {
        Set<String> out = new HashSet<>();
        if (configured == null) {
            return out;
        }
        for (String raw : configured) {
            String v = raw == null ? "" : raw.strip();
            if (v.isEmpty()) {
                continue;
            }
            ParsedOrigin p = parse(v);
            if (p == null) {
                throw new IllegalStateException(property + " entry is not a valid origin "
                        + "(scheme://host[:port], no wildcard, path or trailing slash): " + v);
            }
            out.add(p.canonical());
        }
        return out;
    }

    /** Normalises operator-configured origins (exact, no wildcard); throws on a malformed entry. */
    static Set<String> normaliseOrigins(java.util.List<String> configured, String property) {
        return normaliseConfigured(configured, property);
    }

    private static void assertNoWildcard(java.util.List<String> values, String property) {
        if (values != null && values.stream().anyMatch(v -> v != null && v.strip().equals("*"))) {
            throw new IllegalStateException(property + " must not contain '*': credentials are allowed, so CORS lists are explicit");
        }
    }

    /** Access-ordered, size-bounded map of key to expiry. Expired entries are dropped on read. */
    private static final class Cache {
        private final Map<String, Long> map;

        Cache(int maxEntries) {
            this.map = new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > maxEntries;
                }
            };
        }

        synchronized Boolean get(String key, long now) {
            Long expiry = map.get(key);
            if (expiry == null) {
                return null;
            }
            if (now - expiry >= 0) {
                map.remove(key);
                return null;
            }
            return Boolean.TRUE;
        }

        synchronized void put(String key, long expiry) {
            map.put(key, expiry);
        }

        synchronized void clear() {
            map.clear();
        }
    }
}
