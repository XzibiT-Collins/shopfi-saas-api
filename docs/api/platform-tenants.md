# Platform tenant management API (PLAT-02)

Contract for the platform console (UI-09). All endpoints are under `/api/v1/platform/tenants`, run in the PLATFORM scope
(no tenant context) and read/write only the `master` schema (plus the provisioning saga for approve/reprovision). They never
return tenant business data.

## Authentication and errors

* Cookie auth from PLAT-01: the `platformAccessToken` cookie (HttpOnly, path `/api/v1/platform`). Tenant cookies and tenant
  JWTs (access or refresh) never authenticate here. Browsers must call from an origin listed in
  `platform.cors.allowed-origins`; any other `Origin` gets `403` from CORS (this is also the CSRF guard for the POST actions).
* No cookie, an invalid/expired/tenant token or a deactivated admin: `401`. An authenticated non-SUPER_ADMIN: `403`.
* Every error body is `{"description": "<message>", "data": null}`; success bodies are `{"description": null, "data": ...}`.

| Status | Meaning |
|---|---|
| 400 | bad query parameter (`status`, `page`, `size`, `sort`, `direction`) |
| 401 / 403 | not authenticated / not SUPER_ADMIN |
| 404 | tenant not found. An unknown slug and a malformed slug give the identical response |
| 409 | illegal transition for the tenant's current status, or approve/reprovision without verified credentials (exact texts below) |
| 429 | per-admin action throttle (`platform.tenants.actions-per-minute`, default 30), `Retry-After` header (seconds) set. Standard error envelope, exactly `{"description": "Too many tenant actions, slow down", "data": null}` |
| 500 | the provisioning saga failed; the tenant is now `FAILED` and may be reprovisioned |

## Slug path parameter

`{slug}` must match `^[a-z0-9-]{3,40}$` (3 to 40 characters, lower-case letters, digits and hyphen). Anything else, **including
upper case** (`Acme` is not `acme`), is answered exactly like an unknown tenant: `404` with `{"description": "Tenant not found",
"data": null}`. Malformed slugs never reach SQL or a schema name.

## GET /api/v1/platform/tenants

Query: `status` (optional, one of `PENDING|PROVISIONING|ACTIVE|SUSPENDED|FAILED`, case-insensitive), `page` (0-based, default 0),
`size` (default 20, **capped at 100**: a larger value is silently reduced and the response reports the effective `size`),
`sort` (`createdAt` default, `slug`, `displayName`, `status`), `direction` (`desc` default, `asc`). The unique id is always the
final tiebreaker, so paging is stable.

Fields are registry fields only. `schemaName`, `webhookToken` and anything from a tenant schema are never returned.
`ownerEmail` IS returned, on purpose: the console is SUPER_ADMIN only and needs it to contact the owner of a `PENDING`/`FAILED`
signup. It is personal data, so the console must not log it, cache it in shared storage or show it to anyone below SUPER_ADMIN.

```json example:list-response
{
  "description": null,
  "data": {
    "items": [
      {
        "id": 12,
        "slug": "acme",
        "displayName": "Acme Perfumes",
        "status": "ACTIVE",
        "plan": "FREE",
        "ownerEmail": "owner@acme.example",
        "customDomain": null,
        "createdAt": "2026-11-01T09:30:00.123456",
        "updatedAt": "2026-11-01T09:31:10.654321"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "sort": "createdAt",
    "direction": "desc"
  }
}
```

## Actions

`POST /api/v1/platform/tenants/{slug}/approve | suspend | reactivate | reprovision`. No request body. Response
(`TenantActionResponse`):

* `action`: the action name; `previousStatus`: status before this call; `tenant`: the registry summary AFTER the call.
* `changed`: `true` if this call changed state; `false` for an idempotent no-op (HTTP 200).
* `inProgress`: `true` if the provisioning saga is already running (HTTP 202, `changed=false`).

```json example:action-response
{
  "description": null,
  "data": {
    "action": "SUSPEND",
    "changed": true,
    "inProgress": false,
    "previousStatus": "ACTIVE",
    "tenant": {
      "id": 12,
      "slug": "acme",
      "displayName": "Acme Perfumes",
      "status": "SUSPENDED",
      "plan": "FREE",
      "ownerEmail": "owner@acme.example",
      "customDomain": null,
      "createdAt": "2026-11-01T09:30:00.123456",
      "updatedAt": "2026-11-14T10:00:00.000001"
    }
  }
}
```

Idempotent repeat (already suspended): HTTP 200, `changed=false`, no audit row:

```json example:noop-response
{
  "description": null,
  "data": {
    "action": "SUSPEND",
    "changed": false,
    "inProgress": false,
    "previousStatus": "SUSPENDED",
    "tenant": {
      "id": 12,
      "slug": "acme",
      "displayName": "Acme Perfumes",
      "status": "SUSPENDED",
      "plan": "FREE",
      "ownerEmail": "owner@acme.example",
      "customDomain": null,
      "createdAt": "2026-11-01T09:30:00.123456",
      "updatedAt": "2026-11-14T10:00:00.000001"
    }
  }
}
```

Saga already running (HTTP 202):

```json example:in-progress-response
{
  "description": null,
  "data": {
    "action": "REPROVISION",
    "changed": false,
    "inProgress": true,
    "previousStatus": "PROVISIONING",
    "tenant": {
      "id": 13,
      "slug": "bravo",
      "displayName": "Bravo",
      "status": "PROVISIONING",
      "plan": "FREE",
      "ownerEmail": "owner@bravo.example",
      "customDomain": null,
      "createdAt": "2026-11-14T09:00:00.000000",
      "updatedAt": "2026-11-14T09:59:00.000000"
    }
  }
}
```

Illegal transition (HTTP 409), unknown tenant (HTTP 404):

```json example:error-409
{"description": "Cannot suspend a tenant that is FAILED", "data": null}
```

Exact `409` texts (`description`; `<action>` is the lower-case action, `<STATUS>` the current status):

| Case | `description` |
|---|---|
| any illegal transition (see the state machine) | `Cannot <action> a tenant that is <STATUS>` |
| approve/reprovision, no signup row stored | `Cannot <action> a tenant that is <STATUS>: no onboarding credentials on record` |
| approve/reprovision, signup not verified | `Cannot <action> a tenant that is <STATUS>: the owner has not verified their email` |

A `FAILED` tenant with no stored signup row (the platform keeps no other credential) therefore gets the "no onboarding credentials" 409 on reprovision. A platform-initiated credential reset is deliberately NOT
part of this API (decision recorded in PLAT-02b): the platform never sets or sees an owner password. The owner repeats the
public onboarding signup flow instead; add a separate ticket if operations needs a reset.

```json example:error-404
{"description": "Tenant not found", "data": null}
```

## State machine

Statuses: `PENDING` (signup, not yet provisioned), `PROVISIONING` (saga running), `ACTIVE`, `SUSPENDED`, `FAILED`.
`TRANSITION` = state changes (audited), `NO_OP` = idempotent success, nothing written, `ILLEGAL` = HTTP 409.

| Action | From | Result |
|---|---|---|
| APPROVE | PENDING | TRANSITION |
| APPROVE | PROVISIONING | NO_OP |
| APPROVE | ACTIVE | NO_OP |
| APPROVE | SUSPENDED | ILLEGAL |
| APPROVE | FAILED | ILLEGAL |
| SUSPEND | PENDING | ILLEGAL |
| SUSPEND | PROVISIONING | ILLEGAL |
| SUSPEND | ACTIVE | TRANSITION |
| SUSPEND | SUSPENDED | NO_OP |
| SUSPEND | FAILED | ILLEGAL |
| REACTIVATE | PENDING | ILLEGAL |
| REACTIVATE | PROVISIONING | ILLEGAL |
| REACTIVATE | ACTIVE | NO_OP |
| REACTIVATE | SUSPENDED | TRANSITION |
| REACTIVATE | FAILED | ILLEGAL |
| REPROVISION | PENDING | ILLEGAL |
| REPROVISION | PROVISIONING | NO_OP |
| REPROVISION | ACTIVE | ILLEGAL |
| REPROVISION | SUSPENDED | ILLEGAL |
| REPROVISION | FAILED | TRANSITION |

Meaning of the transitions:

* **suspend**: `ACTIVE -> SUSPENDED`. The tenant answers `423` on its next request and its origin loses CORS immediately (the
  registry is read on every request, and the CORS decision cache is invalidated after the commit). A browser request that
  carries the suspended tenant's `Origin` is refused by the CORS layer (403, no CORS headers) before the 423 can be sent; the 423
  is what Referer/header-resolved and other non-CORS requests receive.
* **reactivate**: `SUSPENDED -> ACTIVE`, effective immediately.
* **approve**: `PENDING -> PROVISIONING -> ACTIVE|FAILED` through the PROV-01 saga, only for a signup whose owner verified their
  email (otherwise 409: the platform never provisions an unverified owner).
* **reprovision**: `FAILED -> PROVISIONING -> ACTIVE|FAILED` through the same saga, using the stored verified credential (hash).
  Two concurrent calls provision once (the saga claim is an atomic compare-and-set); the loser gets a no-op (200/202) or 409.
  A `PROVISIONING` row untouched for `platform.tenants.stuck-provisioning-minutes` (default 15) is treated as a dead saga:
  it is reclaimed to `FAILED` and reprovisioned. An `ACTIVE` tenant is never reprovisioned.
* The calls are synchronous: approve/reprovision return after the saga ends (seconds).

## Audit log

`master.tenant_audit_log` is append-only (database triggers reject `UPDATE`, `DELETE`, `TRUNCATE`; the code has no such path),
so an action that spans several committed steps writes SEVERAL rows. The `phase` column (migration `V7`) says which step a row
records. Idempotent no-ops and rejected calls write nothing, with the single exception of RECONCILE below.

| `phase` | Written by | `previous_status` -> `new_status` | Meaning |
|---|---|---|---|
| `INTENT` | approve / reprovision | `PENDING`/`FAILED` -> `PROVISIONING` | The action was accepted. Committed atomically with the saga claim and BEFORE any schema is created, only by the caller that won the claim (a concurrent loser writes nothing). Exactly one per accepted action. |
| `OUTCOME` | approve / reprovision | `PENDING`/`FAILED` -> `ACTIVE` or `FAILED` | The saga ended. Also the single row of suspend and reactivate (`ACTIVE` <-> `SUSPENDED`, same transaction as the status change). |
| `RECLAIM` | reprovision | `PROVISIONING` -> `FAILED` | A dead saga (untouched for `platform.tenants.stuck-provisioning-minutes`, default 15) was moved to `FAILED`, same transaction as the move; the reprovision then continues with its own INTENT and OUTCOME. Action is `REPROVISION`. |
| `RECONCILE` | approve (no-op on `ACTIVE`) | `ACTIVE` -> `ACTIVE` | A retry found the tenant already `ACTIVE` and repaired a leftover of a crash after the saga: it deleted the still-stored signup password hash and/or recorded an INTENT that never got its OUTCOME. Written only when something was repaired. Action is the retried action. |

Rows written before V7 are `OUTCOME` rows. Columns: `id`, `occurred_at` (database time), `actor_admin_id`, `actor_email` (the
platform admin from the platform JWT, also on RECLAIM and RECONCILE), `tenant_id`, `tenant_slug` (no foreign key, survives a
tenant purge), `action` (`APPROVE`, `SUSPEND`, `REACTIVATE`, `REPROVISION`), `previous_status`, `new_status`, `phase`. The
table holds no passwords, hashes, tokens or payloads. There is no read endpoint yet.

How a console (or an auditor with SQL access) should read it, per tenant ordered by `id`:

* `INTENT` followed by `OUTCOME` (same action): completed; `new_status` of the OUTCOME is the result (`FAILED` means the saga
  failed and the caller received the generic 500).
* `INTENT` with no later `OUTCOME` or `RECONCILE`: the action was accepted but its end was not recorded (crash, or the audit
  write failed). The tenant registry status is authoritative: `ACTIVE` means the saga completed, `FAILED` it did not,
  `PROVISIONING` it is running or dead (a reprovision reclaims it after the stuck window, with a RECLAIM row).
  A retried approve on an `ACTIVE` tenant older than 60 s (`platform.tenants.reconcile-grace-seconds`) closes the gap with a
  RECONCILE row. Never infer that nothing happened because the OUTCOME is missing.
* `RECONCILE` rows attribute the repair to the admin who retried, not to the original actor: the original actor is the one on
  the INTENT row.
* Reprovision of an `ACTIVE` tenant is a 409 (illegal), so only `approve` retries reconcile.

The signup password hash is deleted in its own transaction right after the saga commits, independent of the audit insert; if
that fails the hash is deleted by the reconcile step of the next approve retry (nothing is lost, and a leftover hash is only a
BCrypt hash for a tenant whose owner already exists).

## Deployment notes (PLAT-02b)

* **Database role.** The `tenant_audit_log` triggers only bind SQL issued through the application; the table OWNER (and any
  superuser) can drop or disable them. Run the application as a NON-owner role: connect with a role that has
  `INSERT, SELECT` on `master.tenant_audit_log` (plus `USAGE` on its identity sequence) and no `UPDATE`, `DELETE`,
  `TRUNCATE`, `TRIGGER`, `ALTER`. Flyway migrations (which create and own the table) must run as a different, privileged
  migration role (for example a separate Flyway user), never as the runtime role. Nothing in this repository enforces this:
  it is a deployment responsibility, see the comment in `render.yaml`.
* **Single instance.** After every action the CORS decision cache is dropped with `TenantOriginPolicy.invalidateAll()`, which
  clears the cache of the JVM that handled the request only. With more than one platform/API instance the other instances keep
  their cached allow/deny decision for up to the cache TTL (30 s allowed, 10 s denied) after a suspend, reactivate, approve
  or reprovision. Tenant resolution itself (the 423 for a suspended tenant) reads the registry on every request and is not
  affected. Run a single instance, or add a cluster-wide invalidation (for example Redis pub/sub) before scaling out.

## Examples

```bash
curl -b cookies.txt -H 'Origin: https://console.shopfi.example' \
  'https://api.shopfi.example/api/v1/platform/tenants?status=FAILED&size=50&sort=slug&direction=asc'

curl -b cookies.txt -X POST -H 'Origin: https://console.shopfi.example' \
  https://api.shopfi.example/api/v1/platform/tenants/bravo/reprovision
```
