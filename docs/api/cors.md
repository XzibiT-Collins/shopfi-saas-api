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
- (b) `<scheme>://<custom domain>` of an ACTIVE tenant whose custom domain is `VERIFIED` (DOMAIN-02, registry;
  `PENDING`/`FAILED` domains are ignored). Platform domains, the base domain itself, IP literals and single-label hosts
  are never looked up, and a host under the base domain is decided by its slug ONLY (a custom domain is never consulted
  there). CORS and tenant resolution use the same rule (`tenancy/TenantHostLookup`), so an allowed origin is always
  processed as the same tenant. See [custom-domains.md](custom-domains.md).
- (c) an exact entry of `cors.allowed-origins` (non-tenant origins: local dev, a shared console). Wildcards fail startup.
- Parsing is strict: `http(s)://host[:port]` only. Userinfo, path or trailing slash, trailing dot, unicode or
  punycode (`xn--`) slugs, `null`, empty values, a wildcard and several `Origin` headers are rejected. Hosts are
  compared case-insensitively (the request resolution filter lowercases too).
- Prod profile: `https` and the default port only. Other profiles also accept `http` and an explicit port 1-65535.
- SUSPENDED/PENDING/unknown tenants get no CORS headers (Spring answers 403 "Invalid CORS request").

Several `Origin` headers (e.g. on `GET /api/v1/theme`) are rejected here too, before any controller runs: 403, no CORS grant, and the response is not cacheable (the full 403 header set is `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`, `Pragma: no-cache`, `Expires: 0`, `Vary: Origin`; none of the `Access-Control-Allow-*` headers). A comma-folded `Origin: a, b`, an empty `Origin:` line and two `Origin` lines (whichever is first, allowed or not) are all answered this way by the CORS layer (pinned by `StoreThemeMultipartHttpTest`).

A rejected origin is answered with 403 and no `Access-Control-Allow-*` header; a preflight is answered by the CORS
filter and never reaches tenant resolution. `Access-Control-Allow-Origin` always echoes the single matched origin,
never `*`, and credentials are only enabled alongside an exact origin. `Vary: Origin` (plus the request-method and
request-headers variants) is set on every CORS response, rejected ones included.

## Allowed methods and headers (explicit, no wildcard)

Methods: GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS (`cors.allowed-methods`). Request headers: Content-Type, Accept,
If-Match, If-None-Match, Idempotency-Key (PAY-01 checkout) (`cors.allowed-headers`). `Authorization` is deliberately absent: cookies are the credential.
In non-prod profiles with header resolution on, `X-Tenant-ID` is added so a dev browser can send it. Exposed response
headers: `ETag`, `Retry-After` (`cors.exposed-headers`). Preflight cache: 300 s (`cors.max-age-seconds`).

## Caching, TTL and abuse limits

Registry decisions are cached per host (the key is `h:<host>`; the same shared lookup resolves a base-domain slug or a VERIFIED custom domain) in two bounded LRU maps:

| Property | Default | Meaning |
|---|---|---|
| `cors.tenant-cache.ttl-seconds` (`CORS_TENANT_CACHE_TTL_SECONDS`) | 30 | allowed decisions; a suspension propagates within this time |
| `cors.tenant-cache.negative-ttl-seconds` (`CORS_TENANT_CACHE_NEGATIVE_TTL_SECONDS`) | 10 | denied decisions; a freshly activated tenant becomes usable within this time |
| `cors.tenant-cache.max-entries` / `negative-max-entries` | 5000 / 2000 | separate bounds, so a flood of unknown slugs cannot evict allowed entries |
| `cors.tenant-cache.max-lookups-per-ip-per-second` | 5 | registry lookups for never-seen (or only denied) hosts, per client address; checked FIRST so one client cannot drain the global budget |
| `cors.tenant-cache.max-lookups-per-second` | 50 | the global budget for never-seen hosts; when either budget is empty the origin is denied (fail closed, not cached) |
| `cors.tenant-cache.max-refreshes-per-second` | 100 | a SEPARATE budget for refreshing a host that was recently ALLOWED; unknown hosts cannot use it |
| `cors.tenant-cache.stale-grace-seconds` | 120 | how long past its TTL an ALLOWED decision is still served if the refresh budget is empty or the registry errors; worst-case suspension propagation under attack is `ttl-seconds + stale-grace-seconds` |

A registry error does NOT deny a host that was allowed recently: inside the stale grace window (`stale-grace-seconds`, 120 s
past the TTL) the previous ALLOW is still served; a host that was never allowed (unknown or denied) fails closed on a
registry error and the denial is not cached. Suspension therefore propagates within `ttl-seconds` (30 s) normally and up
to `ttl-seconds + stale-grace-seconds` (150 s) when the refresh budget is exhausted or the registry errors.
`TenantOriginPolicy.invalidateAll()` drops every decision immediately; it is called by the platform suspend/activate/
domain operations (PLAT-02 AC6; not PLAT-03).

Properties (env): `cors.tenant-cache.max-tracked-ips` (bound on the per-client budget map, default 10000),
`cors.tenant-cache.max-lookups-per-ip-per-second`, `cors.tenant-cache.max-lookups-per-second`,
`cors.tenant-cache.max-refreshes-per-second`, `cors.tenant-cache.stale-grace-seconds`, the TTLs and entry bounds above.

Known limitations (by design, fail closed under attack):
- A never-seen new tenant is denied while both lookup budgets (per-client and global) are empty; it is allowed on the
  next request once a token is available.
- An idle tenant whose first request arrives more than 150 s after its last allow, during a botnet flood that drains the
  lookup budgets, is denied until budget is available again (its decision is no longer inside TTL + grace).
- With more than 10,000 simultaneous attacker addresses the per-client map is full and new clients share ONE overflow
  bucket (5 lookups per second): they compete only with each other, never with already tracked clients or with
  recently allowed hosts (those use the separate refresh budget).
- The per-client key is bounded to 64 characters (a resolved client address is an IP literal, otherwise `invalid`). The cache is per JVM instance. Note that tenant
resolution itself (`TenantResolutionFilter`) still checks the status per request, so a suspended tenant's API calls
fail (423) regardless of this cache; the cache only governs whether a browser may read the response.

## Path matching caveat (percent-encoding)

Zone decisions (CORS zone, platform tenant scope, platform token handling) use ONE canonical path: the percent-decoded
path within the application, lower-cased (`security/cors/RequestPaths`), never `getRequestURI()`. A request under
`/api/v1/platform` whose raw path differs from the decoded one (`/api/v1/%70latform/...`, `plat%66orm`, `%6fnboarding`,
`;` parameters, `//`) is answered 400 by `CanonicalPathGuardFilter` before CORS, so a tenant origin can never be granted
credentialed access on a path the router reads as a platform path.

## WebSocket

`/ws` handshakes go through the same CORS filter and `TenantOriginHandshakeInterceptor` (same `TenantOriginPolicy`):
a disallowed Origin gets 403. Spring's static origin check is set to "any" on purpose, because it cannot be dynamic.
SEC-03c: `TenantHandshakeInterceptor` then resolves the tenant exactly like HTTP (`RequestTenantResolver`) and refuses
the handshake when there is none (no Origin/Referer and no dev header: 400; unknown 404; suspended 423), so a
non-browser client must send the tenant's `Origin` (or, outside prod, `X-Tenant-ID`). STOMP frames are authorised per
tenant afterwards (see `authorization-matrix.md` section 2a).

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
`credentials: 'omit'`. A custom domain must be VERIFIED on an ACTIVE tenant before its origin is accepted
(DOMAIN-02: every custom-domain change calls `invalidateAll()` after its commit, so removal or a failed
re-verification takes effect at once on that instance).
