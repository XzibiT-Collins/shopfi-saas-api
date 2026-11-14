# CORS (DOMAIN-01, PLAT-03 AC3, API-01 AC0b)

The API is one host; storefronts and admin UIs run on tenant origins (`https://<slug>.<TENANT_BASE_DOMAIN>` or a
tenant's custom domain). CORS is therefore decided per request from the tenant registry instead of a static list.
Code: `security/cors/DynamicTenantCorsConfigurationSource`, `security/cors/TenantOriginPolicy` (the single decision
function, also used for the WebSocket handshake and the OAuth2 subdomain parsing).

## Three zones

| Path | Allowed origins | Credentials | Notes |
|---|---|---|---|
| `/api/v1/platform/onboarding/**` (public signup wizard) | `platform.cors.allowed-origins` plus the hosts in `tenant.platform-domains` (apex) | NO (`credentials: 'omit'`) | methods GET, POST, OPTIONS; headers Content-Type, Accept |
| `/api/v1/platform/**` (everything else under platform) | ONLY `platform.cors.allowed-origins` | yes | Tenant origins and `cors.allowed-origins` are never accepted here |
| everything else (tenant API, `/ws/**`, ...) | static list (c), active tenant subdomain (a), active tenant custom domain (b) | yes | exposes `ETag`, `Retry-After` |

Tenant origin rules, all enforced before the registry is consulted unless noted:

- (a) `<scheme>://<slug>.<tenant.base-domain>`: exactly ONE label under the base domain (`a.b.base` is refused), a slug
  that passes `TenantSlugs` (3 to 40 chars, `[a-z0-9]` with single hyphens, not reserved: `www`, `api`, `admin`,
  `platform`, ...), and an ACTIVE tenant with that slug (registry).
- (b) `<scheme>://<custom domain>` of an ACTIVE tenant (registry). Platform domains, the base domain itself, IP
  literals and single-label hosts are never looked up.
- (c) an exact entry of `cors.allowed-origins` (non-tenant origins: local dev, a shared console). Wildcards fail startup.
- Parsing is strict: `http(s)://host[:port]` only. Userinfo, path or trailing slash, trailing dot, unicode or
  punycode (`xn--`) slugs, `null`, empty values, a wildcard and several `Origin` headers are rejected. Hosts are
  compared case-insensitively (the request resolution filter lowercases too).
- Prod profile: `https` and the default port only. Other profiles also accept `http` and an explicit port 1-65535.
- SUSPENDED/PENDING/unknown tenants get no CORS headers (Spring answers 403 "Invalid CORS request").

A rejected origin is answered with 403 and no `Access-Control-Allow-*` header; a preflight is answered by the CORS
filter and never reaches tenant resolution. `Access-Control-Allow-Origin` always echoes the single matched origin,
never `*`, and credentials are only enabled alongside an exact origin. `Vary: Origin` (plus the request-method and
request-headers variants) is set on every CORS response, rejected ones included.

## Allowed methods and headers (explicit, no wildcard)

Methods: GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS (`cors.allowed-methods`). Request headers: Content-Type, Accept,
If-Match, If-None-Match (`cors.allowed-headers`). `Authorization` is deliberately absent: cookies are the credential.
In non-prod profiles with header resolution on, `X-Tenant-ID` is added so a dev browser can send it. Exposed response
headers: `ETag`, `Retry-After` (`cors.exposed-headers`). Preflight cache: 300 s (`cors.max-age-seconds`).

## Caching, TTL and abuse limits

Registry decisions are cached per host (`s:<slug>` / `d:<custom-domain>`) in two bounded LRU maps:

| Property | Default | Meaning |
|---|---|---|
| `cors.tenant-cache.ttl-seconds` (`CORS_TENANT_CACHE_TTL_SECONDS`) | 30 | allowed decisions; a suspension propagates within this time |
| `cors.tenant-cache.negative-ttl-seconds` (`CORS_TENANT_CACHE_NEGATIVE_TTL_SECONDS`) | 10 | denied decisions; a freshly activated tenant becomes usable within this time |
| `cors.tenant-cache.max-entries` / `negative-max-entries` | 5000 / 2000 | separate bounds, so a flood of unknown slugs cannot evict allowed entries |
| `cors.tenant-cache.max-lookups-per-second` | 50 | one shared budget for cache misses; when empty the origin is denied (fail closed, not cached) |

A registry error also denies the origin and is not cached. `TenantOriginPolicy.invalidateAll()` drops every decision
immediately; PLAT-03's suspend/activate operations should call it. The cache is per JVM instance. Note that tenant
resolution itself (`TenantResolutionFilter`) still checks the status per request, so a suspended tenant's API calls
fail (423) regardless of this cache; the cache only governs whether a browser may read the response.

## WebSocket

`/ws` handshakes go through the same CORS filter and `TenantOriginHandshakeInterceptor` (same `TenantOriginPolicy`):
a disallowed Origin gets 403; a missing Origin (non-browser) or the API's own origin passes and STOMP frames are
still authenticated. Spring's static origin check is set to "any" on purpose, because it cannot be dynamic.

## Deployment environment variables

| Variable | Required in prod | Meaning |
|---|---|---|
| `TENANT_BASE_DOMAIN` | yes | tenants are `<slug>.TENANT_BASE_DOMAIN` |
| `TENANT_PLATFORM_DOMAINS` | yes | apex/API hosts; their origins may call public onboarding (no credentials) |
| `PLATFORM_CORS_ALLOWED_ORIGINS` | for the console | comma-separated exact origins of the platform-admin console. Default EMPTY: the platform API is unreachable from browsers until set |
| `CORS_ALLOWED_ORIGINS` | no | non-tenant origins only (default empty in prod; dev default is localhost) |
| `CORS_ALLOWED_METHODS`, `CORS_ALLOWED_HEADERS` | no | override the explicit lists (a `*` fails startup) |
| `CORS_TENANT_CACHE_TTL_SECONDS`, `CORS_TENANT_CACHE_NEGATIVE_TTL_SECONDS` | no | see above |

Frontend note: send tenant API calls with `credentials: 'include'`; the public onboarding wizard uses
`credentials: 'omit'`. A custom domain must be set on an ACTIVE tenant before its origin is accepted.
