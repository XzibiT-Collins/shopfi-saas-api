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
    private final long graceNanos;
    private final TokenBucketRateLimiter missLimiter;
    private final TokenBucketRateLimiter ipMissLimiter;
    private final TokenBucketRateLimiter refreshLimiter;
    private final LongSupplier clock;
    /** Bumped by {@link #invalidateAll()}; a lookup that started under an older generation never stores its result. */
    private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();

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
        this.graceNanos = Duration.ofSeconds(Math.max(0, c.getStaleGraceSeconds())).toNanos();
        this.missLimiter = new TokenBucketRateLimiter(Math.max(1, c.getMaxLookupsPerSecond()),
                Duration.ofSeconds(1), 4, clock);
        this.ipMissLimiter = new TokenBucketRateLimiter(Math.max(1, c.getMaxLookupsPerIpPerSecond()),
                Duration.ofSeconds(1), Math.max(1, c.getMaxTrackedIps()), clock);
        this.refreshLimiter = new TokenBucketRateLimiter(Math.max(1, c.getMaxRefreshesPerSecond()),
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
        return isAllowed(origin, "-");
    }

    /**
     * @param clientKey the rate-limit key of the caller (see {@code ClientIps#rateLimitKey}); registry lookups for
     *                  keys never seen before are budgeted per client first, then globally
     */
    public boolean isAllowed(String origin, String clientKey) {
        ParsedOrigin p = parse(origin);
        if (p == null) {
            return false;
        }
        if (staticOrigins.contains(p.canonical())) {
            return true;
        }
        return isActiveTenantOrigin(p, clientKey == null ? "-" : clientKey);
    }

    /** True if the origin is an ACTIVE tenant's subdomain or custom domain (no static list). */
    public boolean isTenantOrigin(String origin) {
        ParsedOrigin p = parse(origin);
        return p != null && isActiveTenantOrigin(p, "-");
    }

    /** Whether the origin passes the scheme/port rules for tenant hosts (https and default port only in prod). */
    public boolean schemeAndPortAllowed(ParsedOrigin p) {
        return !prod || ("https".equals(p.scheme()) && p.port() == 0);
    }

    /**
     * Mirrors {@code TenantResolutionFilter}: a custom domain match wins over the slug, and the decision is taken
     * for THAT tenant, so an origin approved here is always processed as the same tenant (DOMAIN-01b S3).
     */
    private boolean isActiveTenantOrigin(ParsedOrigin p, String clientKey) {
        if (!schemeAndPortAllowed(p)) {
            return false;
        }
        String host = p.host();
        String base = tenantProps.getBaseDomain() == null ? "" : tenantProps.getBaseDomain().strip().toLowerCase(Locale.ROOT);
        String slug = null;
        if (!base.isEmpty() && (host.equals(base) || host.endsWith("." + base))) {
            slug = subdomainSlug(host, base);
            if (slug == null) {
                return false; // syntactically not a tenant host: no registry access for garbage
            }
        } else if (tenantProps.isPlatformDomain(host) || IPV4.matcher(host).matches() || !host.contains(".")) {
            return false;
        }
        if (tenantProps.isPlatformDomain(host)) {
            return false;
        }
        final String slugFinal = slug;
        return cachedActive("h:" + host, clientKey, () -> {
            java.util.Optional<Tenant> byDomain = registry.findByCustomDomain(host);
            if (byDomain.isPresent()) {
                return byDomain;
            }
            return slugFinal == null ? java.util.Optional.<Tenant>empty() : registry.findBySlug(slugFinal);
        });
    }

    private boolean cachedActive(String key, String clientKey, java.util.function.Supplier<java.util.Optional<Tenant>> lookup) {
        long now = clock.getAsLong();
        int state = allowedCache.state(key, now, graceNanos);
        if (state == Cache.FRESH) {
            return true;
        }
        if (state == Cache.MISSING && deniedCache.state(key, now, 0) == Cache.FRESH) {
            return false;
        }
        // state is MISSING (never seen or only denied/expired) or STALE (a recently ALLOWED key past its TTL).
        boolean stale = state == Cache.STALE;
        if (stale) {
            // Protected lane: refreshing a known-allowed key has its own budget that unknown keys cannot drain.
            if (refreshLimiter.tryAcquire("refresh") != 0) {
                return true; // serve the stale ALLOW (bounded by the grace window) rather than failing a known tenant
            }
        } else if (ipMissLimiter.tryAcquire(clientKey) != 0 || missLimiter.tryAcquire("registry") != 0) {
            log.warn("CORS registry lookups are rate limited; denying origin without caching");
            return false; // fail closed, only for keys never seen allowed
        }
        long gen = generation.get();
        boolean active;
        try {
            active = lookup.get().map(t -> t.getStatus() == TenantStatus.ACTIVE).orElse(false);
        } catch (RuntimeException e) {
            log.warn("CORS tenant lookup failed: {}", e.toString());
            return stale; // a known-allowed key stays allowed within the grace window; unknown keys fail closed
        }
        if (generation.get() == gen) { // invalidateAll() during the lookup: do not store a possibly stale answer
            if (active) {
                deniedCache.remove(key);
                allowedCache.put(key, now + ttlNanos);
            } else {
                allowedCache.remove(key);
                deniedCache.put(key, now + negativeTtlNanos);
            }
        }
        return active;
    }

    /**
     * Drops every cached decision (call after a tenant is suspended, activated or its domain changes, AFTER the DB
     * transaction has committed, or a concurrent lookup could read the old row and be re-cached). Per JVM
     * instance only: other instances converge within the TTL (plus the stale grace window under lookup pressure).
     */
    public void invalidateAll() {
        generation.incrementAndGet();
        allowedCache.clear();
        deniedCache.clear();
    }

    /**
     * Test support: refills the registry-lookup, per-client and refresh budgets. Does not change their capacity, rate or
     * the fail-closed behaviour, and nothing in production calls it ({@code invalidateAll()} clears caches only).
     */
    void resetBudgets() {
        missLimiter.reset();
        ipMissLimiter.reset();
        refreshLimiter.reset();
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

    /** Access-ordered, size-bounded map of key to expiry. */
    private static final class Cache {
        static final int MISSING = 0;
        static final int FRESH = 1;
        static final int STALE = 2;

        private final Map<String, Long> map;

        Cache(int maxEntries) {
            this.map = new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > maxEntries;
                }
            };
        }

        /** FRESH before expiry, STALE within {@code graceNanos} after it, otherwise MISSING (and dropped). */
        synchronized int state(String key, long now, long graceNanos) {
            Long expiry = map.get(key);
            if (expiry == null) {
                return MISSING;
            }
            if (now - expiry < 0) {
                return FRESH;
            }
            if (now - expiry < graceNanos) {
                return STALE;
            }
            map.remove(key);
            return MISSING;
        }

        synchronized void put(String key, long expiry) {
            map.put(key, expiry);
        }

        synchronized void remove(String key) {
            map.remove(key);
        }

        synchronized void clear() {
            map.clear();
        }
    }
}
