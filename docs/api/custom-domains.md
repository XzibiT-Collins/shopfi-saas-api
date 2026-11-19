# Custom domains API (DOMAIN-02, DOMAIN-03)

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
| `DOMAIN_INVALID` | not a syntactically valid FQDN: scheme, path, port, userinfo, wildcard, underscore or any character outside `[a-z0-9.-]` after IDN conversion; empty label, label over 63 or name over 253 characters; label starting/ending with `-`; `--` in positions 3-4 of a non-IDN label; `xn--` label that is not valid punycode; a single label; a top-level label not starting with a letter; a Unicode name containing `ß`, `ẞ`, final sigma `ς` or a zero-width joiner (IDNA2003 would store `straße.de` as `strasse.de`, browsers send `xn--strae-oqa.de`: such names are refused, spell the name without them) |
| `DOMAIN_PLATFORM` | equal to, under or a parent of `tenant.base-domain`, any `tenant.platform-domains` host, or any host of `cors.allowed-origins` / `platform.cors.allowed-origins`, or the CNAME target `tenant.custom-domain.cname-target`. Example: tenant `ydom` claiming `acme.shopfi.test` (the DOMAIN-01 collision) |
| `DOMAIN_CONFUSABLE` | visually confusable with one of those platform names (NFKD with marks removed, Cyrillic/Greek/Armenian look-alikes, `0`/`5` folded to Latin, `i`/`l`/`1` one class, `rn`/`vv` read as `m`/`w`), e.g. `xn--shpfi-kye.app` (Cyrillic "о"), `sh0pfi.app` or `shopf1.app` when the base domain is `shopfi.app` |
| `DOMAIN_RESERVED` | `localhost` and reserved/internal names and everything under them: `localhost`, `localdomain`, `local`, `internal`, `intranet`, `lan`, `home`, `corp`, `private`, `test`, `example`, `invalid`, `onion`, `arpa`, `alt`, `home.arpa`, `example.com`, `example.net`, `example.org`, plus `tenant.custom-domain.reserved-suffixes` |

Uniqueness: one domain belongs to at most one tenant, compared case-insensitively. Enforced by the service and by the
master database (`uq_tenants_custom_domain_lower` on `lower(custom_domain)`, plus check constraints for the stored
shape and for a consistent domain/status/token triple). Two tenants claiming the same domain at the same time: exactly
one wins, the other gets `409 DOMAIN_TAKEN`.

A `PENDING`/`FAILED` claim may be taken by another tenant's claim once BOTH of these are older than
`tenant.custom-domain.unverified-claim-ttl-hours` (72):

- its claim time, and
- the last time it was seen `VERIFIED` (`custom_domain_last_verified_at`: set on every successful verification and
  stamped with the current time when a re-verification turns `VERIFIED` into `FAILED`).

This is the anti-squatting rule; the old claim is cleared and audited as `RELEASE` by `SYSTEM`. A claim 71 h 59 min old
is still protected. A `VERIFIED` domain is never taken over, and a domain that was `VERIFIED` until a failed
re-verification keeps that protection for the full 72 h counted from that failure (however long ago it was last
verified), so the owner can restore the record and verify again. A claim that keeps failing does not extend it, and
neither does refreshing a claim with verify calls (only a successful verification, or the `VERIFIED` to `FAILED`
change, does).

## Statuses

| Status | Meaning | Honoured? |
|---|---|---|
| `PENDING` | claimed, proof not checked yet (also every pre-DOMAIN-02 value set by direct DB edit; master migration V8) | no |
| `VERIFIED` | the TXT proof was found at the last check (re-checked on a schedule, see below) | yes |
| `FAILED` | the last check did not find the proof, or the stored domain no longer passes the rules above (`failureReason` = the `DOMAIN_*` code) | no |

Transitions: `PUT` (new or different domain) -> `PENDING` with a fresh token. `POST .../verify` -> `VERIFIED` or
`FAILED` (any status, so it is also the **manual re-verification**: a `VERIFIED` domain whose record disappeared becomes
`FAILED` and stops being honoured at once; the scheduled job downgrades only after a grace period). `DELETE` -> no domain. `PUT` with the domain already set is a no-op that
keeps the token (the published TXT record stays valid).

## Ownership proof (DNS TXT)

Publish `TXT <txtRecordName> "<txtRecordValue>"`, i.e. `_shopfi-verification.<domain>` with the value
`shopfi-verification=<token>` (both returned by every endpoint; the token is random per claim and not a secret).
The record must match exactly (other TXT records on the name are ignored; a multi-string record is joined).
The lookup runs through `DnsTxtResolver` (`JndiDnsTxtResolver`: the JDK's built-in JNDI DNS client, no extra
dependency) with `tenant.custom-domain.dns-timeout-millis` (2000) and `dns-retries` (1 query round; see Configuration), against
`tenant.custom-domain.dns-servers` or the system resolver. NXDOMAIN or no matching record is a definitive `FAILED`;
a timeout/SERVFAIL changes nothing and answers `503 DOMAIN_DNS_UNAVAILABLE`. An HTTP challenge is not implemented
(optional in the ticket). The resolver itself refuses anything that is not a plain dotted host name (no URL-shaped
names reach JNDI).

**Keep the TXT record published, for as long as you use the domain.** A `VERIFIED` domain is re-checked in the
background (next section). If the record stays gone, the domain becomes `FAILED` and stops being honoured: CORS, tenant
resolution and OAuth2 redirects. This protects you: a lapsed domain bought by someone else must not stay trusted by the
platform. Do not remove `_shopfi-verification.<domain>` after the first successful verify. A manual verify (tenant or
platform admin) still checks at once and downgrades at once.

## Scheduled re-verification (DOMAIN-03)

A background job re-checks the TXT proof of every `VERIFIED` domain (`CustomDomainReverifier`, started by
`CustomDomainReverifyScheduler` on its own thread). It is **on in the `prod` profile and off everywhere else** (tests,
dev): `tenant.custom-domain.reverify.enabled`.

**Policy** (per domain; defaults in Configuration):

| Check result | Effect |
|---|---|
| proof found | stays `VERIFIED`; miss streak cleared; `checked_at`, `verified_at`, `last_verified_at` = now; next check in `interval` (+ jitter) |
| DNS gave no definite answer (timeout, SERVFAIL, network error; the resolver's 2 s timeout applies) | **nothing about trust changes**: no downgrade, the miss streak is neither extended nor cleared; retry in `error-retry-interval` (+ jitter). Any length of DNS outage never downgrades a domain |
| definite miss (NXDOMAIN, no TXT, or no TXT with the exact value: a changed token counts too) | counts as one miss; the first miss of a streak starts the grace period; retry in `miss-retry-interval` (+ jitter). The domain stays `VERIFIED` and honoured until BOTH `misses-required` (3) consecutive definite misses AND `grace` (24 h) since the first miss are reached; then `VERIFIED` -> `FAILED` |
| the stored domain no longer passes the rules above (e.g. the platform base domain or CNAME target changed) | `FAILED` at once with the `DOMAIN_*` code (a local rule, no DNS is involved, no grace) |

**Exposure window (what the grace costs).** With the defaults, after the TXT record is removed the domain can stay
trusted for roughly 48 to 60 h in the worst case: the first check that notices it comes 24 to 28.8 h after the last
good one (`interval` plus up to 20 % jitter), then the downgrade needs 3 misses spanning at least 24 h at 6 to 7.2 h
spacing (`miss-retry-interval` plus jitter), i.e. the first check 24 h or more after the first miss. Shorten `interval`,
`miss-retry-interval` and `grace` to trade tolerance for a smaller window. A DNS outage only delays this. A platform
admin (or the owner) can verify or remove at any time for an immediate effect.

**Limits of the TXT proof.** The proof is "the record exists", not "the current registrant published it": someone who
buys a lapsed domain could replay the old record (the token is not secret; it can be seen in passive DNS history or old
zone copies) and keep the domain trusted. That is inherent to a static TXT proof; the scheduled check only catches
domains whose record really disappeared. Removing the domain (or rotating the claim with `PUT` of a different domain and
back) issues a fresh token.

**Throughput.** One round checks at most `batch-size` (10) domains and rounds start `tick-interval` (1 min) after the
previous one ended: at most about 14,400 domains per day per instance (less when DNS is slow), so `interval` should be
chosen with the number of VERIFIED domains in mind. The job's own bucket (`dns-per-minute`, 20) and the global verify
bucket (`verify-global-per-minute`, 60) are **per JVM**, like every other limiter here: with N instances the totals are
N times higher.

Configuration is validated at start-up: zero or negative durations, `batch-size`, `misses-required` or `dns-per-minute`
below 1 and `jitter-percent` outside 0..100 stop the application from starting; `misses-required=1` together with
`grace=0` is accepted with a warning (a single bad resolver answer would downgrade a domain).

**Downgrade** (one transaction, row lock, same as a failed manual verify): status `FAILED`, `failureReason`
`TXT_RECORD_NOT_FOUND` (or the `DOMAIN_*` code), `verified_at` cleared, `checked_at` and `last_verified_at` = now (the
F1b rule: the owner keeps the anti-squatting protection for the full `unverified-claim-ttl-hours` counted from the
downgrade, and can restore the record and verify again), schedule and miss streak cleared, ONE audit row (below). After
the commit `TenantOriginPolicy.invalidateAll()` flips the CORS decision cache of that instance at once; tenant
resolution and the OAuth2 redirect gate read the registry per request (`TenantHostLookup` has no cache), so they stop
trusting the domain on the next request on every instance. Other instances' CORS caches converge within the CORS cache TTL
(see Caches). Owners are not emailed (no notification channel is wired for this): they see `FAILED` and the
`failureReason` in the admin settings, and the platform history shows the `REVERIFY` row.

**How rows are picked** (multi-instance safe, bounded, no thundering herd):

- State lives on `master.tenants` (migration V9): `custom_domain_reverify_due_at`, `custom_domain_miss_count`,
  `custom_domain_missing_since`; a database check constraint allows them only on `VERIFIED` rows. A new claim, a
  removal, a manual verify to `FAILED`, or a downgrade clears them; a manual verify to `VERIFIED` restarts the schedule.
- A round claims ONE row at a time with a single atomic statement
  (`UPDATE ... WHERE id = (SELECT ... WHERE status = 'VERIFIED' AND (due IS NULL OR due <= now) ORDER BY due LIMIT 1
  FOR UPDATE SKIP LOCKED) RETURNING ...`) that moves the row's due time `lease` (10 min) ahead. Two instances (or two
  rounds) never check the same row at once, a row locked by an admin write is skipped not waited for, no row lock is
  held while DNS is queried (the result is applied under the row lock only if domain, token, status `VERIFIED` and the row's
  due time (= this claim's lease end) are still the ones that were checked; the status check is redundant defence in depth, because the V9 constraint only allows a due time on a `VERIFIED` row: an answer that arrives after the claim was
  changed, or after the lease ended and another round took the row, is dropped, so a miss is never counted twice), and the rows of an instance that crashed mid-check come back when the lease ends.
- A round checks at most `batch-size` (10) domains and a round is `tick-interval` (1 min) after the previous one ends,
  so a backlog is worked off gradually (most overdue first) instead of in a burst. Every schedule gets a random extra
  delay of up to `jitter-percent` (20 %) of its base delay, and the V9 migration spreads the first check of existing
  `VERIFIED` domains over the first 24 h.
- DNS budget: each lookup takes one token from the job's own bucket (`dns-per-minute`, 20) AND one from the global
  verify bucket (`verify-global-per-minute`, 60) that manual verifies use too, so the job can never use more than 20 of
  the 60 per minute and a manual verify keeps at least 40. When a bucket is empty the claimed row is handed back
  untouched and the round ends. The per-tenant 10 verifies/hour limit is for manual verifies and is not spent by the job.
- The job runs without a tenant context and reads and writes only the master registry and the master audit log (one
  tenant's row per transaction); it never touches a tenant schema. It never logs a token, a TXT value or DNS error text
  (tenant slug and outcome only).

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
`actor_email`, `tenant_id`, `tenant_slug`, `action` (`REQUEST`, `VERIFY`, `REMOVE`, `RELEASE`, `REVERIFY`), `domain`,
`previous_domain` (on a replace), `previous_status`, `new_status`, `detail` (`REPLACED`, `TXT_RECORD_MATCHED`,
`TXT_RECORD_NOT_FOUND`, a `DOMAIN_*` code, `UNVERIFIED_CLAIM_EXPIRED`). No token is stored there. A DNS check without
a definitive answer writes no row. `REVERIFY` (actor `SYSTEM`, no actor id/email) is written once per scheduled
downgrade (`previous_status` `VERIFIED`, `new_status` `FAILED`, `detail` `TXT_RECORD_NOT_FOUND` or a `DOMAIN_*` code);
counted misses, DNS errors and successful re-checks are not transitions and write no row (they are in the log, without
tokens).

## Caches and multiple instances

Every write calls `TenantOriginPolicy.invalidateAll()` after its transaction ends, so the CORS decision cache of THAT
instance flips immediately (also when a re-verification, manual or scheduled, fails). Other instances converge within
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
| `tenant.custom-domain.dns-timeout-millis` / `dns-retries` | 2000 / 1 | `dns-retries` is the number of JNDI query rounds (clamped to 1..4); round i waits `timeout * 2^i` per server, so the worst case is `timeout * (2^rounds - 1) * servers`: 2 s per server with the defaults (measured 6.1 s for 3 silent servers) |
| `tenant.custom-domain.verify-per-tenant-per-hour` / `verify-global-per-minute` | 10 / 60 | verification rate limits |
| `tenant.custom-domain.changes-per-tenant-per-hour` | 20 | claim/remove rate limit (tenant admin) |
| `tenant.custom-domain.reverify.enabled` (`TENANT_CUSTOM_DOMAIN_REVERIFY_ENABLED`) | `false`; `true` in the `prod` profile | scheduled re-verification on/off |
| `reverify.tick-interval` / `initial-delay` / `batch-size` | 1m / 2m / 10 | pause between rounds, delay after start-up, domains per round at most |
| `reverify.interval` / `jitter-percent` | 24h / 20 | time between checks of an OK domain; random extra up to this % of any delay |
| `reverify.miss-retry-interval` / `error-retry-interval` | 6h / 30m | next check after a definite miss / after a DNS error |
| `reverify.misses-required` / `grace` | 3 / 24h | downgrade needs both: this many consecutive definite misses and this long since the first |
| `reverify.lease` | 10m | a claimed row is reserved this long (minimum 60 s) |
| `reverify.dns-per-minute` | 20 | the job's own DNS budget per JVM, on top of the shared global 60/min |
| `tenant.custom-domain.unverified-claim-ttl-hours` | 72 | an unverified claim can be taken by another tenant once its claim time and the last time it was seen VERIFIED are both older than this |

## Frontend follow-up (not in this ticket)

Admin settings page "Custom domain" (tenant admin): enter domain (`PUT`), show `txtRecordName`/`txtRecordValue` and
`cnameTarget` with copy buttons, "Verify now" (`POST .../verify`, show `failureReason`, respect `Retry-After`), remove.
Platform console: show `customDomainStatus` in the tenant list/detail, the domain history, re-verify and remove.
