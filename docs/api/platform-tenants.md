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
| 409 | illegal transition for the tenant's current status (see below) |
| 429 | per-admin action throttle (`platform.tenants.actions-per-minute`, default 30), `Retry-After` header set |
| 500 | the provisioning saga failed; the tenant is now `FAILED` and may be reprovisioned |

## GET /api/v1/platform/tenants

Query: `status` (optional, one of `PENDING|PROVISIONING|ACTIVE|SUSPENDED|FAILED`, case-insensitive), `page` (0-based, default 0),
`size` (default 20, **capped at 100**: a larger value is silently reduced and the response reports the effective `size`),
`sort` (`createdAt` default, `slug`, `displayName`, `status`), `direction` (`desc` default, `asc`). The unique id is always the
final tiebreaker, so paging is stable.

Fields are registry fields only. `schemaName`, `webhookToken` and anything from a tenant schema are never returned.

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

Every state-changing call writes exactly one row to `master.tenant_audit_log` (idempotent no-ops and rejected calls write
none). Suspend/reactivate write it in the same transaction as the status change; approve/reprovision write it once after the
saga ended, by the caller that won the saga claim (`newStatus` is `ACTIVE`, or `FAILED` when the saga failed).

| Field | Meaning |
|---|---|
| `id`, `occurred_at` | sequence id, database timestamp |
| `actor_admin_id`, `actor_email` | the platform admin from the platform JWT |
| `tenant_id`, `tenant_slug` | the tenant acted on (no foreign key, survives tenant purge) |
| `action` | `APPROVE`, `SUSPEND`, `REACTIVATE`, `REPROVISION` |
| `previous_status`, `new_status` | status before and after |

The table holds no passwords, hashes, tokens or payloads. It is append-only: no API can update or delete it and database
triggers reject `UPDATE`, `DELETE` and `TRUNCATE`. There is no read endpoint yet.

## Examples

```bash
curl -b cookies.txt -H 'Origin: https://console.shopfi.example' \
  'https://api.shopfi.example/api/v1/platform/tenants?status=FAILED&size=50&sort=slug&direction=asc'

curl -b cookies.txt -X POST -H 'Origin: https://console.shopfi.example' \
  https://api.shopfi.example/api/v1/platform/tenants/bravo/reprovision
```
