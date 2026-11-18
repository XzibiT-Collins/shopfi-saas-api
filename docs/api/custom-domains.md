# Custom domains API (DOMAIN-02)

A tenant can serve its storefront and admin UI on its own domain (e.g. `shop.acme-brand.com`) in addition to
`<slug>.<TENANT_BASE_DOMAIN>`. A custom domain is **honoured only while it is `VERIFIED`**: CORS
(`TenantOriginPolicy`), tenant resolution (`TenantResolutionFilter` and the WebSocket handshake), the OAuth2
`redirect_uri` gate and emailed links (`TenantLinkBuilder`: password reset, OAuth2 failure page, checkout callback) all
read it through one shared rule, `tenancy/TenantHostLookup` / `TenantRegistryService.findByVerifiedCustomDomain`.
A `PENDING` or `FAILED` domain changes nothing anywhere.

Deployment (DNS, wildcard and custom-domain certificates, CNAME target, env vars): `QUICK_START.md`, section
"Custom domains: DNS and TLS".

## Who may do what

| Actor | Endpoints | Why |
|---|---|---|
| Tenant ADMIN (own shop only; the tenant is the request's resolved tenant, never a parameter) | read, claim/replace, verify, remove | the shop owner controls their DNS; claiming is harmless because nothing is honoured before the DNS proof |
| Platform SUPER_ADMIN (`/api/v1/platform/tenants/{slug}/custom-domain`) | read with audit history, re-verify, remove | support and abuse handling (e.g. remove a domain the tenant no longer controls). A platform admin cannot claim a domain for a tenant: ownership must be proven by the tenant's DNS anyway |

CUSTOMER and FRONT_DESK get `403`; anonymous and foreign-tenant tokens `401` (same rules as every admin route, see
`authorization-matrix.md`).

## Rules for a domain (`PUT`, also re-checked on every verify)

The value is trimmed, lower-cased, ONE trailing dot is removed and an internationalised name is stored in its ASCII
(punycode) form, e.g. `Bücher.de.` is stored as `xn--bcher-kva.de`. Then (first failing rule wins):

| `data.code` | Rejected |
|---|---|
| `DOMAIN_REQUIRED` | missing, null or blank `domain` |
| `DOMAIN_IP_LITERAL` | IPv4 in any notation a resolver accepts (`10.0.0.1`, `3232235786`, `0x7f000001`, `0177.0.0.1`, `127.1`, full-width digits) and IPv6 (`::1`, `[::1]`, `fe80::1%eth0`) |
| `DOMAIN_INVALID` | not a syntactically valid FQDN: scheme, path, port, userinfo, wildcard, underscore or any character outside `[a-z0-9.-]` after IDN conversion; empty label, label over 63 or name over 253 characters; label starting/ending with `-`; `--` in positions 3-4 of a non-IDN label; `xn--` label that is not valid punycode; a single label; a top-level label not starting with a letter |
| `DOMAIN_PLATFORM` | equal to, under or a parent of `tenant.base-domain`, any `tenant.platform-domains` host, or any host of `cors.allowed-origins` / `platform.cors.allowed-origins`. Example: tenant `ydom` claiming `acme.shopfi.test` (the DOMAIN-01 collision) |
| `DOMAIN_CONFUSABLE` | visually confusable with one of those platform names (NFKD with marks removed, Cyrillic/Greek/Armenian look-alikes and `0`/`1`/`rn`/`vv` folded to Latin), e.g. `xn--shpfi-kye.app` (Cyrillic "о") or `sh0pfi.app` when the base domain is `shopfi.app` |
| `DOMAIN_RESERVED` | `localhost` and reserved/internal names and everything under them: `localhost`, `localdomain`, `local`, `internal`, `intranet`, `lan`, `home`, `corp`, `private`, `test`, `example`, `invalid`, `onion`, `arpa`, `alt`, `home.arpa`, `example.com`, `example.net`, `example.org`, plus `tenant.custom-domain.reserved-suffixes` |

Uniqueness: one domain belongs to at most one tenant, compared case-insensitively. Enforced by the service and by the
master database (`uq_tenants_custom_domain_lower` on `lower(custom_domain)`, plus check constraints for the stored
shape and for a consistent domain/status/token triple). Two tenants claiming the same domain at the same time: exactly
one wins, the other gets `409 DOMAIN_TAKEN`. A claim that is still `PENDING`/`FAILED` after
`tenant.custom-domain.unverified-claim-ttl-hours` (72) may be taken by another tenant's claim (anti-squatting; the old
claim is cleared and audited as `RELEASE` by `SYSTEM`). A `VERIFIED` domain is never taken over.

## Statuses

| Status | Meaning | Honoured? |
|---|---|---|
| `PENDING` | claimed, proof not checked yet (also every pre-DOMAIN-02 value set by direct DB edit; master migration V8) | no |
| `VERIFIED` | the TXT proof was found at the last check | yes |
| `FAILED` | the last check did not find the proof, or the stored domain no longer passes the rules above (`failureReason` = the `DOMAIN_*` code) | no |

Transitions: `PUT` (new or different domain) -> `PENDING` with a fresh token. `POST .../verify` -> `VERIFIED` or
`FAILED` (any status, so it is also the **re-verification**: a `VERIFIED` domain whose record disappeared becomes
`FAILED` and stops being honoured at once). `DELETE` -> no domain. `PUT` with the domain already set is a no-op that
keeps the token (the published TXT record stays valid).

## Ownership proof (DNS TXT)

Publish `TXT <txtRecordName> "<txtRecordValue>"`, i.e. `_shopfi-verification.<domain>` with the value
`shopfi-verification=<token>` (both returned by every endpoint; the token is random per claim and not a secret).
The record must match exactly (other TXT records on the name are ignored; a multi-string record is joined).
The lookup runs through `DnsTxtResolver` (`JndiDnsTxtResolver`: the JDK's built-in JNDI DNS client, no extra
dependency) with `tenant.custom-domain.dns-timeout-millis` (2000) and `dns-retries` (1), against
`tenant.custom-domain.dns-servers` or the system resolver. NXDOMAIN or no matching record is a definitive `FAILED`;
a timeout/SERVFAIL changes nothing and answers `503 DOMAIN_DNS_UNAVAILABLE`. An HTTP challenge is not implemented
(optional in the ticket).

The CNAME (`cnameTarget`, from `TENANT_CUSTOM_DOMAIN_CNAME_TARGET`) routes traffic; it is shown for convenience and
not checked by the backend.

## Response body (`CustomDomainView`)

Every tenant endpoint answers `200` with the current state. With no domain set, every field except `cnameTarget` is
`null`.

```json example:view-response
{
  "description": null,
  "data": {
    "domain": "shop.acme-brand.com",
    "status": "PENDING",
    "txtRecordName": "_shopfi-verification.shop.acme-brand.com",
    "txtRecordValue": "shopfi-verification=3q2-7wEAAABx_Kc0Qm9vdGgxMjM0NTY3OA",
    "cnameTarget": "stores.shopfi.app",
    "requestedAt": "2026-11-18T10:15:30.123456Z",
    "checkedAt": null,
    "verifiedAt": null,
    "failureReason": null
  }
}
```

`failureReason`: `TXT_RECORD_NOT_FOUND`, or a `DOMAIN_*` code when a stored (legacy) value breaks the rules.

## Tenant admin endpoints (`hasRole('ADMIN')`, tenant from Origin / dev header)

| Method + path | Body | Success | Errors |
|---|---|---|---|
| `GET /api/v1/admin/custom-domain` | none | `200` view | |
| `PUT /api/v1/admin/custom-domain` | `{"domain": "shop.acme-brand.com"}` | `200` view (`PENDING`) | `400 DOMAIN_*`, `409 DOMAIN_TAKEN`, `429 DOMAIN_CHANGE_RATE_LIMITED`, `503 CUSTOM_DOMAIN_BUSY` |
| `POST /api/v1/admin/custom-domain/verify` | none | `200` view (`VERIFIED` or `FAILED`) | `404 DOMAIN_NOT_SET`, `429 DOMAIN_VERIFY_RATE_LIMITED`, `503 DOMAIN_DNS_UNAVAILABLE`, `503 CUSTOM_DOMAIN_BUSY` |
| `DELETE /api/v1/admin/custom-domain` | none | `200` view (empty); idempotent | `429 DOMAIN_CHANGE_RATE_LIMITED`, `503 CUSTOM_DOMAIN_BUSY` |

## Platform endpoints (`SUPER_ADMIN`, cookie auth and CORS as in `platform-tenants.md`)

| Method + path | Success | Errors |
|---|---|---|
| `GET /api/v1/platform/tenants/{slug}/custom-domain` | `200` `{tenantSlug, customDomain: <view>, history: [<audit row>...]}` (newest first, max 50) | `404` tenant not found (unknown or malformed slug, standard `{"description": "Tenant not found", "data": null}`) |
| `POST /api/v1/platform/tenants/{slug}/custom-domain/verify` | `200` view | as the tenant verify, plus `404` |
| `DELETE /api/v1/platform/tenants/{slug}/custom-domain` | `200` view (empty); idempotent | `404`, `503 CUSTOM_DOMAIN_BUSY` |

The tenant list/detail (`GET /api/v1/platform/tenants[/{slug}]`) also returns `customDomainStatus` next to
`customDomain` (`null` when no domain is set).

```json example:platform-response
{
  "description": null,
  "data": {
    "tenantSlug": "acme",
    "customDomain": {
      "domain": "shop.acme-brand.com",
      "status": "VERIFIED",
      "txtRecordName": "_shopfi-verification.shop.acme-brand.com",
      "txtRecordValue": "shopfi-verification=3q2-7wEAAABx_Kc0Qm9vdGgxMjM0NTY3OA",
      "cnameTarget": "stores.shopfi.app",
      "requestedAt": "2026-11-18T10:15:30.123456Z",
      "checkedAt": "2026-11-18T10:20:02.000001Z",
      "verifiedAt": "2026-11-18T10:20:02.000001Z",
      "failureReason": null
    },
    "history": [
      {
        "id": 42,
        "action": "VERIFY",
        "actorType": "TENANT_ADMIN",
        "actorEmail": "owner@acme-brand.com",
        "domain": "shop.acme-brand.com",
        "previousDomain": null,
        "previousStatus": "PENDING",
        "newStatus": "VERIFIED",
        "detail": "TXT_RECORD_MATCHED",
        "occurredAt": "2026-11-18T10:20:02.000001Z"
      }
    ]
  }
}
```

## Errors

Standard envelope; branch on the status and `data.code`, `description` is display text only and never echoes the
submitted domain.

```json example:error-400
{
  "description": "This domain belongs to the platform and cannot be used as a custom domain.",
  "data": {"code": "DOMAIN_PLATFORM"}
}
```

| Status | `data.code` | When |
|---|---|---|
| 400 | `DOMAIN_REQUIRED`, `DOMAIN_INVALID`, `DOMAIN_IP_LITERAL`, `DOMAIN_PLATFORM`, `DOMAIN_CONFUSABLE`, `DOMAIN_RESERVED` | rules above |
| 404 | `DOMAIN_NOT_SET` | verify with no domain |
| 409 | `DOMAIN_TAKEN` | another tenant holds the domain (any status, unless its unverified claim expired). The owner is not revealed |
| 429 | `DOMAIN_VERIFY_RATE_LIMITED` | more than `verify-per-tenant-per-hour` (10) checks for this tenant (tenant and platform admins have separate buckets), or more than `verify-global-per-minute` (60) DNS checks overall; `Retry-After` header (seconds) |
| 429 | `DOMAIN_CHANGE_RATE_LIMITED` | more than `changes-per-tenant-per-hour` (20) claims/removals by the tenant admin; `Retry-After` |
| 503 | `DOMAIN_DNS_UNAVAILABLE` | DNS gave no definitive answer; nothing changed; `Retry-After: 30` |
| 503 | `CUSTOM_DOMAIN_BUSY` | the tenant row stayed locked longer than the lock timeout (another change in flight); `Retry-After: 1` |

## Audit (master)

Every change writes one row to `master.tenant_domain_audit_log` in the same transaction (append-only: database
triggers reject UPDATE/DELETE/TRUNCATE): `actor_type` (`TENANT_ADMIN`, `PLATFORM_ADMIN`, `SYSTEM`), `actor_id`,
`actor_email`, `tenant_id`, `tenant_slug`, `action` (`REQUEST`, `VERIFY`, `REMOVE`, `RELEASE`), `domain`,
`previous_domain` (on a replace), `previous_status`, `new_status`, `detail` (`REPLACED`, `TXT_RECORD_MATCHED`,
`TXT_RECORD_NOT_FOUND`, a `DOMAIN_*` code, `UNVERIFIED_CLAIM_EXPIRED`). No token is stored there. A DNS check without
a definitive answer writes no row.

## Caches and multiple instances

Every write calls `TenantOriginPolicy.invalidateAll()` after its transaction ends, so the CORS decision cache of THAT
instance flips immediately (also when a re-verification fails). Other instances converge within
`CORS_TENANT_CACHE_TTL_SECONDS` (30 s; up to TTL + stale grace under lookup pressure, see `cors.md`). Tenant resolution
has no cache (it reads the registry per request), so API calls stop resolving on the next request everywhere. Rate
limits are per instance too.

## Configuration

| Property (env) | Default | Meaning |
|---|---|---|
| `tenant.custom-domain.cname-target` (`TENANT_CUSTOM_DOMAIN_CNAME_TARGET`) | empty | shown as `cnameTarget` |
| `tenant.custom-domain.txt-label` (`TENANT_CUSTOM_DOMAIN_TXT_LABEL`) | `_shopfi-verification` | TXT record name prefix |
| `tenant.custom-domain.txt-value-prefix` | `shopfi-verification=` | TXT value prefix |
| `tenant.custom-domain.reserved-suffixes` (`TENANT_CUSTOM_DOMAIN_RESERVED_SUFFIXES`) | empty | extra names nobody may claim |
| `tenant.custom-domain.dns-servers` (`TENANT_CUSTOM_DOMAIN_DNS_SERVERS`) | system resolver | `host[:port]` list |
| `tenant.custom-domain.dns-timeout-millis` / `dns-retries` | 2000 / 1 | JNDI doubles the timeout per retry: worst case 6 s per server |
| `tenant.custom-domain.verify-per-tenant-per-hour` / `verify-global-per-minute` | 10 / 60 | verification rate limits |
| `tenant.custom-domain.changes-per-tenant-per-hour` | 20 | claim/remove rate limit (tenant admin) |
| `tenant.custom-domain.unverified-claim-ttl-hours` | 72 | after this an unverified claim can be taken by another tenant |

## Frontend follow-up (not in this ticket)

Admin settings page "Custom domain" (tenant admin): enter domain (`PUT`), show `txtRecordName`/`txtRecordValue` and
`cnameTarget` with copy buttons, "Verify now" (`POST .../verify`, show `failureReason`, respect `Retry-After`), remove.
Platform console: show `customDomainStatus` in the tenant list/detail, the domain history, re-verify and remove.
