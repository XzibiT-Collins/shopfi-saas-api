# Tenant Onboarding API (PROV-03)

Status: **v1, documents the behaviour of PROV-02 / PROV-02b as merged on `feature/PROV-02-onboarding-api`.** Source of truth for the public self-service signup flow used by the admin wizard (UI-06).

| Artifact | Path |
|---|---|
| This document | `backend/docs/api/onboarding.md` |
| JSON Schema (draft 2020-12) for request and response bodies | `backend/docs/api/onboarding.schema.json` |
| Generated OpenAPI (springdoc) for the onboarding controller | `backend/docs/api/openapi-onboarding.json` |
| Contract test | `backend/src/test/java/com/example/perfume_budget/contract/OnboardingContractTest.java` |

Every example below tagged `<!-- example: <name> <kind> <Schema> [status] -->` is extracted by the contract test, validated against `onboarding.schema.json`, and exercised against the real controller on PostgreSQL: `valid` request examples must be accepted (and are used as the payload of the happy path), `invalid` request examples must be rejected with the stated HTTP status, and `response` examples must equal (description, data keys, data values where deterministic) what the server actually returns for the named scenario. Changing code, schema or doc out of step breaks the build.

## 1. Conventions

- Base path: `/api/v1/platform/onboarding`. Public (`permitAll`), no cookie, no JWT, no CSRF token (CSRF is disabled app-wide).
- **Platform scope, no tenant needed.** Everything under `/api/v1/platform/**` is bound to the platform context by `TenantResolutionFilter` regardless of `Origin`/`Referer`. The wizard runs before any tenant exists, so no `Origin` header, `X-Tenant-ID` or tenant host is required, and an `Origin` that matches no tenant does **not** produce the usual tenant-resolution `404`. (A browser `Origin` that is not in the CORS allow-list is rejected earlier by the CORS layer, section 11.)
- JSON in, JSON out, camelCase. Unknown request properties are ignored by the server (Jackson default); the schema marks them `additionalProperties: false`, so do not send them.
- Every body, success or error, is the existing envelope `CustomApiResponse`: `{ "description": string|null, "data": T|null }`. `data` is always present (possibly `null`).
- **There is no machine-readable error code field.** The platform-wide `GlobalExceptionHandler` returns only `description` (human text) and, for bean-validation failures, a field map in `data`. Clients must branch on the **HTTP status** (and, for the few cases noted, on the exact status plus endpoint), and must not parse `description` for logic. Treat `description` strings as display text that may be reworded.

## 2. Endpoints at a glance

| Endpoint | Success | Purpose |
|---|---|---|
| `POST /signup` | `201` | Create a PENDING tenant and e-mail a 6-digit code to the owner |
| `POST /verify` | `202` | Check the code; on success start provisioning asynchronously |
| `POST /resend` | `202` (always) | Issue a fresh code (expired, lost, or after a FAILED attempt) |
| `GET /status?slug=` | `200` | Poll the onboarding state; URLs once ACTIVE |

## 3. `POST /signup`

Request (`TenantSignupRequest`):

| Field | Type | Rules |
|---|---|---|
| `slug` | string, required | Section 8. Trimmed and lowercased by the server. 400 if invalid or reserved |
| `displayName` | string, required | 1..255, no control characters, not blank after trim |
| `ownerEmail` | string, required | Valid e-mail, <= 255. Lowercased and trimmed; matching is case-insensitive |
| `ownerFullName` | string, required | 1..255, no control characters, not blank after trim |
| `password` | string, required | Section 9 |
| `plan` | `FREE` \| `STARTER` \| `PRO`, optional | Defaults to `FREE`. Records intent only, no billing |

<!-- example: signup-request valid TenantSignupRequest -->
```json
{
  "slug": "acme-store",
  "displayName": "Acme Store",
  "ownerEmail": "owner@acme-store.example",
  "ownerFullName": "Ada Owner",
  "password": "Correct-Horse-9",
  "plan": "FREE"
}
```

<!-- example: signup-request-minimal valid TenantSignupRequest -->
```json
{
  "slug": "bravo-goods",
  "displayName": "Bravo Goods",
  "ownerEmail": "hello@bravo-goods.example",
  "ownerFullName": "Ben Owner",
  "password": "Another-Pass-1"
}
```

Success, `201 Created`. The tenant exists with status `PENDING`; the code is e-mailed after the transaction commits. The response never contains the e-mail, password, code, schema name or ids.

<!-- example: signup-created response StatusEnvelope 201 -->
```json
{
  "description": "Verification code sent",
  "data": { "slug": "acme-store", "status": "PENDING" }
}
```

Failures:

| HTTP | When | `description` (data) |
|---|---|---|
| 400 | A field is missing, blank, too long, malformed e-mail, control characters | `Validation failed`, `data` = `{ "<field>": "<message>" }` |
| 400 | Body is not JSON, empty, or a wrong type (for example an unknown `plan`) | `Request body is missing or malformed.` |
| 400 | Slug longer than 64 characters (bean validation) | `Validation failed`, `data` = `{ "slug": "<message>" }` (the 3-40 rule below is not reached) |
| 400 | Slug length (under 3 or 41-64), characters or reserved name | `slug must be 3-40 characters`, `slug may contain only lowercase letters, digits and single hyphens between them`, `slug is reserved` |
| 400 | Password too short or over 72 UTF-8 bytes | `Password must be at least 8 characters and at most 72 bytes` |
| 409 | Slug **or** owner e-mail already registered (including a FAILED tenant), or a concurrent duplicate | `Slug or owner email is already registered` |
| 429 | Rate limit (section 7) | `Too many requests. Please try again later.` + `Retry-After` |

Notes: a stale PENDING signup (default older than 24 h, `onboarding.pending-ttl-hours`) is purged when the same slug or e-mail signs up again, so a later signup can succeed where an earlier one would have returned 409. A live signup cannot be overwritten. The 409 does not say whether slug or e-mail collided. Bean-validation 400s are produced before the rate limiter runs and do not consume tokens; slug and password 400s run after it and do.

<!-- example: signup-conflict response ErrorEnvelope 409 -->
```json
{
  "description": "Slug or owner email is already registered",
  "data": null
}
```

<!-- example: signup-validation-error response ErrorEnvelope 400 -->
```json
{
  "description": "Validation failed",
  "data": { "ownerEmail": "must be a well-formed email address" }
}
```

<!-- example: signup-slug-reserved-error response ErrorEnvelope 400 -->
```json
{
  "description": "slug is reserved",
  "data": null
}
```

<!-- example: signup-malformed-body-error response ErrorEnvelope 400 -->
```json
{
  "description": "Request body is missing or malformed.",
  "data": null
}
```

Rejected request examples (the contract test posts each and expects the stated status):

<!-- example: signup-slug-too-short invalid TenantSignupRequest 400 -->
```json
{ "slug": "ab", "displayName": "Tiny", "ownerEmail": "o@tiny.example", "ownerFullName": "Tina", "password": "Correct-Horse-9" }
```

<!-- example: signup-slug-reserved invalid TenantSignupRequest 400 -->
```json
{ "slug": "admin", "displayName": "Admin", "ownerEmail": "o@admin.example", "ownerFullName": "Al", "password": "Correct-Horse-9" }
```

<!-- example: signup-slug-bad-characters invalid TenantSignupRequest 400 -->
```json
{ "slug": "acme_store", "displayName": "Acme", "ownerEmail": "o@acme.example", "ownerFullName": "Al", "password": "Correct-Horse-9" }
```

<!-- example: signup-slug-double-hyphen invalid TenantSignupRequest 400 -->
```json
{ "slug": "acme--store", "displayName": "Acme", "ownerEmail": "o@acme.example", "ownerFullName": "Al", "password": "Correct-Horse-9" }
```

<!-- example: signup-password-short invalid TenantSignupRequest 400 -->
```json
{ "slug": "short-pass", "displayName": "Short", "ownerEmail": "o@short.example", "ownerFullName": "Sho", "password": "short" }
```

<!-- example: signup-email-malformed invalid TenantSignupRequest 400 -->
```json
{ "slug": "bad-mail", "displayName": "Bad Mail", "ownerEmail": "not-an-email", "ownerFullName": "Bad", "password": "Correct-Horse-9" }
```

<!-- example: signup-missing-field invalid TenantSignupRequest 400 -->
```json
{ "slug": "no-password", "displayName": "No Password", "ownerEmail": "o@nopass.example", "ownerFullName": "Nop" }
```

<!-- example: signup-unknown-plan invalid TenantSignupRequest 400 -->
```json
{ "slug": "gold-plan", "displayName": "Gold", "ownerEmail": "o@gold.example", "ownerFullName": "Gil", "password": "Correct-Horse-9", "plan": "GOLD" }
```

## 4. `POST /verify`

Request (`TenantVerifyRequest`): `slug` (required, <= 64), `email` (the owner e-mail used at signup, required), `otp` (required, <= 16, the 6-digit code; surrounding whitespace is stripped).

<!-- example: verify-request valid TenantVerifyRequest -->
```json
{ "slug": "acme-store", "email": "owner@acme-store.example", "otp": "123456" }
```

Success, `202 Accepted`: the code was correct, it is **burned** (single use) and provisioning has been queued on a dedicated executor. Provisioning has not finished: poll `/status`. The body reports `PROVISIONING` (see the state machine in section 6 for why a verified `PENDING` tenant reads as `PROVISIONING`).

<!-- example: verify-accepted response StatusEnvelope 202 -->
```json
{
  "description": "Provisioning started",
  "data": { "slug": "acme-store", "status": "PROVISIONING" }
}
```

Failures:

| HTTP | When | `description` |
|---|---|---|
| 400 | Wrong code, expired code (10 min), already-used code, unknown slug, malformed slug, e-mail does not match the signup, tenant already ACTIVE/PROVISIONING/SUSPENDED | `Invalid or expired verification code` (identical for all, so it does not reveal which) |
| 400 | Missing or blank field, malformed e-mail | `Validation failed` + field map, or `Request body is missing or malformed.` |
| 429 | **Lockout**: 5 wrong codes lock the signup for 15 min. While locked, even the **correct** code returns 429 | `Too many incorrect codes. Try again later.` + `Retry-After` (seconds until the lock ends) |
| 429 | IP or e-mail rate limit | `Too many requests. Please try again later.` + `Retry-After` |
| 429 | Provisioning executor is full (rare). The code is handed back so the same code can be retried | `Onboarding is busy, retry shortly` + `Retry-After: 30` |

Each failed attempt increments a counter; the counter resets only on a successful verify or when a lock expires on its own. A resend never resets it and never unlocks (section 5). Note the one existence signal: a `429 Too many incorrect codes` is only returned for a real slug+e-mail pair that has been locked, whereas unknown pairs always get 400.

<!-- example: verify-invalid-code response ErrorEnvelope 400 -->
```json
{
  "description": "Invalid or expired verification code",
  "data": null
}
```

<!-- example: verify-locked response ErrorEnvelope 429 -->
```json
{
  "description": "Too many incorrect codes. Try again later.",
  "data": null
}
```

<!-- example: verify-request-missing-otp invalid TenantVerifyRequest 400 -->
```json
{ "slug": "acme-store", "email": "owner@acme-store.example" }
```

## 5. `POST /resend`

Request (`TenantResendRequest`): `slug`, `email`.

<!-- example: resend-request valid TenantResendRequest -->
```json
{ "slug": "acme-store", "email": "owner@acme-store.example" }
```

**Enumeration-safe: always `202` with the identical body**, whatever the outcome.

<!-- example: resend-accepted response ResendEnvelope 202 -->
```json
{
  "description": "If a pending signup matches, a new verification code has been sent",
  "data": null
}
```

A new code is actually issued (the old one stops working, valid 10 min) only when **all** hold: the slug and e-mail match a signup whose tenant is `PENDING` (not yet verified) or `FAILED`; it is not locked; the cooldown since the last code has elapsed (`onboarding.resend-cooldown-seconds`, 60); fewer than `onboarding.max-resends` (3) resends have been used for this signup (the first code does not count). Otherwise it silently does nothing, and the client cannot tell. The code is mailed only to the e-mail stored at signup. Resend never clears a lockout and never resets the failed-attempt counter.

Failures (the only non-202 outcomes): `400` validation / malformed body as above, and `429` rate limit (`Retry-After`).

Frontend consequence: because success and no-op look the same, the UI must say "if the details match, a code is on its way", enforce its own 60 s countdown, and not promise delivery.

## 6. `GET /status?slug=<slug>` and the state machine

`slug` is a required query parameter (missing: `400 Missing required parameter: slug.`). Case and surrounding whitespace are normalised. A slug that is malformed, unknown or whose tenant is `SUSPENDED` returns `404 Unknown tenant` (suspended is deliberately indistinguishable from nonexistent). `429` when over the per-IP limit.

`data`: `slug`, `status` in `PENDING | PROVISIONING | ACTIVE | FAILED`, and **only when `ACTIVE`** (and `tenant.base-domain` is configured) `storefrontUrl` and `adminLoginUrl`. Absent keys are omitted, not `null`. The e-mail, ids and schema name are never returned.

<!-- example: status-pending response StatusEnvelope 200 -->
```json
{ "description": null, "data": { "slug": "acme-store", "status": "PENDING" } }
```

<!-- example: status-provisioning response StatusEnvelope 200 -->
```json
{ "description": null, "data": { "slug": "acme-store", "status": "PROVISIONING" } }
```

<!-- example: status-active response StatusEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "slug": "acme-store",
    "status": "ACTIVE",
    "storefrontUrl": "https://acme-store.shopfi.test",
    "adminLoginUrl": "https://acme-store.shopfi.test/admin/login"
  }
}
```

<!-- example: status-failed response StatusEnvelope 200 -->
```json
{ "description": null, "data": { "slug": "acme-store", "status": "FAILED" } }
```

<!-- example: status-unknown response ErrorEnvelope 404 -->
```json
{ "description": "Unknown tenant", "data": null }
```

### State machine

```
                 signup (201)
   (no tenant) ─────────────────▶ PENDING
                                    │  (row: verified_at = null)
                  verify OK (202)   │
                  code burned,      ▼
                  verified_at set  PENDING + verified_at   ── reported as PROVISIONING
                  job queued       (DB state)
                                    │  worker claims PENDING/FAILED
                                    ▼  -> PROVISIONING (atomic CAS)
                               PROVISIONING
                               │            │
                  saga succeeds│            │saga fails
                               ▼            ▼
                             ACTIVE       FAILED
                          (terminal;     (retryable: POST /resend issues a new code and
                          URLs appear;   clears verified_at, the tenant status is NOT changed;
                          signup row,    /status keeps answering FAILED until the next
                          password hash  successful POST /verify queues a new attempt)
                          deleted)
   SUSPENDED (platform action, outside onboarding): /status answers 404.
```

Rules worth coding against:

- The API reports a verified-but-not-yet-claimed `PENDING` tenant as `PROVISIONING`; that is why `verify` answers `PROVISIONING` at once. `PENDING` in a poll therefore always means "waiting for the owner's code".
- `PENDING` before the code is entered never moves on its own. An abandoned signup is purged after 24 h but only when someone signs up with the same slug or e-mail (there is no scheduled purge yet).
- `FAILED` is recoverable: the old code is dead (single use), so call `/resend`, then `/verify` with the new code. `/resend` never changes the tenant status: `/status` keeps returning `FAILED` after a resend and only moves on after the next `/verify` is accepted (then `PROVISIONING`). After `max-resends` (3) failures and resends the signup is stuck until the purge, and a user-facing "contact support" path is needed. A FAILED tenant cannot be re-signed-up (`409`).
- **Crash window.** If the JVM dies after `/verify` was accepted but before the provisioning worker claims the tenant, it stays `PENDING` with `verified_at` set and `/status` reports `PROVISIONING` indefinitely: `/resend` is a no-op for it, `/verify` answers `400` (the code is burned), and no sweeper exists, so nothing recovers it until the 24 h purge triggered by a signup that reuses its slug or e-mail. The UI's 3 minute polling timeout and "contact support" path cover it (see PROV-04/PROV-05).
- `ACTIVE` is final for onboarding. Provisioning typically takes a few seconds (schema creation and migrations) but has no documented upper bound.

## 7. Rate limiting and client IP

Token buckets, in memory **per JVM instance** (N instances multiply the effective limit; a restart resets them). Burst equals capacity; tokens refill continuously at `capacity / period`. Defaults (override with `onboarding.<name>.capacity` / `.period-seconds`):

| Limiter | Key | Capacity | Period |
|---|---|---|---|
| `signup-ip` | client IP | 5 | 3600 s |
| `signup-email` | owner e-mail (lowercased) | 3 | 3600 s |
| `verify-ip` | client IP | 20 | 600 s |
| `verify-email` | e-mail | 10 | 600 s |
| `status-ip` | client IP | 60 | 60 s |
| `resend-ip` | client IP | 10 | 3600 s |
| `resend-email` | e-mail | 3 | 3600 s |

A throttled request gets `429`, `Retry-After: <whole seconds, >= 1>`, and `Too many requests. Please try again later.` Both the IP and the e-mail limiter must pass.

<!-- example: rate-limited response ErrorEnvelope 429 -->
```json
{
  "description": "Too many requests. Please try again later.",
  "data": null
}
```
 IPv6 clients are bucketed by /64. At most `onboarding.max-tracked-keys` (10,000) keys are tracked per limiter; past that, new keys share one overflow bucket (fail closed), so a flood from many addresses degrades to a global limit and legitimate users may see 429 early.

Per-e-mail buckets are keyed on the e-mail in the body, so anyone can spend another person's e-mail budget (for example three signups or ten verifies in the window). The OTP lockout (5 wrong codes, 15 min) can be triggered the same way by someone who knows the slug and e-mail. Both are bounded and documented; the UI should show the countdown and the resend path rather than blame the user.

### Client-IP and proxy trust requirement (operations)

The per-IP limits key on `request.getRemoteAddr()`. Behind a reverse proxy this is correct only if the deployment is configured as shipped: `server.forward-headers-strategy=native` with Tomcat's `RemoteIpValve`, which walks `X-Forwarded-For` from the right, skips hops matching `server.tomcat.remoteip.internal-proxies`, and takes the first untrusted hop. If the direct peer is not a trusted proxy the forwarding headers are ignored. `X-Real-IP` and `Forwarded` are never used.

- The default `internal-proxies` regex trusts private and loopback ranges (what Render's router uses). If a CDN or another proxy tier sits in front, set `TRUSTED_PROXY_REGEX` to cover **all and only** your proxies.
- **The app must not be reachable directly from a private or NAT'd peer.** If it is, a client sending `X-Forwarded-For: <anything>` chooses its own rate-limit bucket. Never set the strategy to `framework` (leftmost, client-controlled entry).
- Frontend implication: a Next.js server that calls this API on behalf of browsers (SSR, route handler, BFF) becomes the single client IP for all users unless it is a trusted proxy that appends the real address to `X-Forwarded-For`; otherwise every visitor shares one signup bucket (5 per hour). Prefer calling from the browser, or forward the client IP and add the hop to `TRUSTED_PROXY_REGEX`.

## 8. Slug rules

Normalised (trim, lowercase) then checked against an allow-list (the slug becomes the PostgreSQL schema `tenant_<slug with - as _>` and the hostname label):

- 3 to 40 characters.
- Lowercase letters `a-z`, digits, and hyphens; a hyphen only **between** alphanumerics (no leading, trailing or doubled hyphen). Regex `^[a-z0-9]+(-[a-z0-9]+)*$`. No underscores, dots or Unicode.
- Reserved (400 `slug is reserved`): `public`, `master`, `tenant-template`, `template`, `tenant`, `tenants`, `www`, `api`, `admin`, `platform`, `postgres`, `information-schema`, `root`, `system`, `internal`, plus anything starting with `pg-` or `tenant-` (their underscore spellings are reserved too).
- Uniqueness is checked at signup (`409`); there is no "is this slug free?" endpoint, so a client cannot pre-check availability without attempting signup (open question, section 12).

The wizard should mirror the rules client-side, but the server is authoritative. Note: `/status` and `/verify` accept any 1..64 character slug string; only invalid ones simply never match.

## 9. Password rules

- Surrounding whitespace is **trimmed** before checking and hashing (the stored credential is the trimmed value; the admin logs in with the trimmed password).
- At least **8** characters after trimming; at most **72 UTF-8 bytes** (BCrypt limit; multi-byte characters count more than once). Bean validation also caps the raw field at 200 characters.
- No other policy (no complexity or common-password check). The UI may be stricter.
- The password is BCrypt-hashed during signup, stored only as a hash until provisioning completes, then deleted from the signup row and moved into the new tenant's admin user. It is never returned or logged.

## 10. Storefront and admin URLs

Returned only when `status` is `ACTIVE`:

```
storefrontUrl = <onboarding.public-scheme>://<slug>.<tenant.base-domain>            (public-scheme default: https)
adminLoginUrl = storefrontUrl + <onboarding.admin-login-path>                        (default: /admin/login)
```

`tenant.base-domain` comes from `TENANT_BASE_DOMAIN` in prod. If it is blank (the dev default), `ACTIVE` is returned **without** URLs, so the client must handle absent URLs (show success and the slug, not a broken link). The URLs use the slug returned by the server, not the one typed. Custom domains are not reflected here.

## 11. CORS

Onboarding is its own CORS zone (DOMAIN-01; see `docs/api/cors.md`, `security/cors/DynamicTenantCorsConfigurationSource`):

- Allowed origins for `/api/v1/platform/onboarding/**`: the exact list in `platform.cors.allowed-origins` (`PLATFORM_CORS_ALLOWED_ORIGINS`) plus the hosts in `tenant.platform-domains` (apex). Default is EMPTY in the base and prod properties, so the origin that serves the signup wizard (the platform or marketing site, not a tenant host) must be configured or the browser blocks the call. Tenant origins and `cors.allowed-origins` are not accepted here. Exact-match only (no wildcards).
- Methods GET, POST, OPTIONS; headers `Content-Type`, `Accept`; credentials are NOT allowed (use `credentials: "omit"`).
- `Retry-After` and `ETag` are exposed (`Access-Control-Expose-Headers: ETag, Retry-After`), so a cross-origin browser `fetch` can read `Retry-After` on 429 responses.
- An `Origin` header that is **not** allowed is rejected by the CORS layer with `403` and the plain-text body `Invalid CORS request` (not the JSON envelope), for simple requests as well as preflights. Clients without an `Origin` (curl, server-to-server) are unaffected, and a tenant-less `Origin` is not a tenant failure on platform paths.
- No cookies are involved, so `credentials: "include"` is unnecessary for these endpoints.

## 12. Guidance for frontend implementers

Flow: `signup` -> OTP screen -> `verify` -> progress screen polling `status` -> success with links.

- **Polling cadence.** After `verify` returns 202, poll `GET /status` every 2 s for the first 20 s, then every 5 s, with jitter, until `ACTIVE` or `FAILED`. Stop after about 3 minutes and show "taking longer than usual, we will keep trying / contact support" while still allowing a manual refresh. The `status-ip` budget is 60 per minute per IP, so never poll faster than 1 per second. Treat a transient network error or 5xx as "keep polling"; treat `404` as an error (the tenant vanished or was suspended). Pause polling while the tab is hidden.
- **ACTIVE.** Link to `storefrontUrl` and `adminLoginUrl` when present; otherwise show the slug and say where to sign in. The owner signs in with the e-mail and the trimmed password from signup.
- **FAILED.** Show a clear error and offer "Send me a new code" (calls `/resend`, then asks for the code again), with a contact path if resends are exhausted. Do not auto-retry.
- **Wrong code (400).** Show one generic message ("That code is invalid or has expired") and a remaining-attempts hint is not available (the server does not return it). Allow at most 5 tries in the UI.
- **Lockout (429 on verify).** Disable the input, show "Too many incorrect codes. Try again in N minutes" using `Retry-After` when readable (else 15 minutes). Resend does **not** unlock; do not suggest it as a way out while locked.
- **Resend.** Enforce a 60 s countdown before enabling the button, show at most 3 resends, and use neutral wording ("If the details match, we sent a new code") because the response is identical in every case. A new code invalidates the previous one: say so.
- **409 on signup.** Say the slug or e-mail is already registered, offer another slug, and do not claim which one. For "my signup is stuck" show the resend path.
- **429 anywhere.** Back off using `Retry-After` if present, else the documented window; never retry in a tight loop.
- **Never log or persist the password** (client logs, analytics, error trackers, Redux or URL state, `localStorage`). Do not echo it back or put it in query strings. Clear it from memory after signup returns. Keep the OTP out of logs and analytics too. Redact request bodies in Sentry or similar for these routes.
- Treat `description` as display-only; branch on HTTP status. For `400` with an object in `data`, map `data[field]` to the form field (`slug`, `displayName`, `ownerEmail`, `ownerFullName`, `password`); the other `400`s (slug and password rules) carry only `description`, so show them next to the slug or password field based on which rules you pre-checked.

## 13. Known gaps (for the board, not fixed here)

1. No machine-readable error codes in the shared envelope; clients must key on HTTP status. A stable `code` field would need a change in `GlobalExceptionHandler` and every client.
2. (Resolved by DOMAIN-01: `Retry-After` is now exposed via CORS, section 11.)
3. No slug-availability endpoint (section 8).
4. FAILED owners get at most 3 resends; after that the signup is stuck until the 24 h purge, which only runs on slug or e-mail reuse.
5. Rate limits are per JVM; there is no shared (Redis) limiter.
6. Swagger UI and `/v3/api-docs` are `permitAll` and not disabled in prod; tracked as SEC-01 (see `openapi-onboarding.json`).
