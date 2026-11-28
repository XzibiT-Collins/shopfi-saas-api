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
| 400 | bad query parameter (`status`, `page`, `size`, `sort`, `direction`, `search`, `customDomainStatus`, `hasCustomDomain`) |
| 401 / 403 | not authenticated / not SUPER_ADMIN |
| 404 | tenant not found (detail, audit and every action). An unknown slug and a malformed slug give the identical response |
| 409 | illegal transition for the tenant's current status, or approve/reprovision without verified credentials (exact texts below) |
| 429 | per-admin action throttle (`platform.tenants.actions-per-minute`, default 30) on the four POST actions and, from PLAN-01, on `PUT /tenants/{slug}/plan` ([plans.md](plans.md) section 11), `Retry-After` header (seconds) set. Standard error envelope, exactly `{"description": "Too many tenant actions, slow down", "data": null}` |
| 500 | the provisioning saga failed; the tenant is now `FAILED` and may be reprovisioned |

## Slug path parameter

`{slug}` must match `^[a-z0-9-]{3,40}$` (3 to 40 characters, lower-case letters, digits and hyphen). Anything else, **including
upper case** (`Acme` is not `acme`), is answered exactly like an unknown tenant: `404` with `{"description": "Tenant not found",
"data": null}`. Malformed slugs never reach SQL or a schema name.

## GET /api/v1/platform/tenants

Query: `status` (optional, one of `PENDING|PROVISIONING|ACTIVE|SUSPENDED|FAILED`, case-insensitive), `page` (0-based, default 0),
`size` (default 20, **capped at 100**: a larger value is silently reduced and the response reports the effective `size`),
`sort` (`createdAt` default, `slug`, `displayName`, `status`), `direction` (`desc` default, `asc`), `search` (PLAT-04, see below),
`customDomainStatus` and `hasCustomDomain` (API-RD-07, see below). The unique id is always the
final tiebreaker, so paging is stable.

Fields are registry fields only. `schemaName`, `webhookToken` and anything from a tenant schema are never returned.
`customDomainStatus` (DOMAIN-02) is `PENDING`, `VERIFIED` or `FAILED` (`null` without a custom domain); only a `VERIFIED`
domain is live. Custom-domain management: [custom-domains.md](custom-domains.md).
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
        "plan": "STARTER",
        "planName": "Starter",
        "subscriptionStatus": "TRIALING",
        "trialEndsAt": "2026-11-15T09:31:10.654321Z",
        "ownerEmail": "owner@acme.example",
        "customDomain": null,
        "customDomainStatus": null,
        "createdAt": "2026-11-01T09:30:00.123456",
        "updatedAt": "2026-11-01T09:31:10.654321"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "sort": "createdAt",
    "direction": "desc",
    "customDomainCounts": { "PENDING": 0, "VERIFIED": 0, "FAILED": 0, "NONE": 1 }
  }
}
```

### `customDomainStatus`, `hasCustomDomain` and `customDomainCounts` (API-RD-07 AC2)

* `customDomainStatus=PENDING|VERIFIED|FAILED` (case-insensitive) keeps only tenants whose custom domain is in that state.
  Anything else (including `NONE`, which is a count key and not a stored state) is `400` `Unknown customDomainStatus filter`.
* `hasCustomDomain=true` keeps tenants that have a custom domain in any state; `false` keeps tenants without one. A value
  that is not `true`/`false` is `400`.
* All filters (`status`, `search`, `customDomainStatus`, `hasCustomDomain`) combine with AND, so
  `customDomainStatus=VERIFIED&hasCustomDomain=false` is an empty page (`200`), not an error.
* `customDomainCounts` is **always present** and is the page-header facet: the number of tenants per custom-domain state
  (`PENDING`, `VERIFIED`, `FAILED`, and `NONE` = no custom domain) among the tenants that match `status` and `search`.
  It deliberately **ignores** `customDomainStatus`, `hasCustomDomain`, `page` and `size`, so every count says how many rows
  that filter would show and the chips do not change when one is selected. The four counts add up to the number of tenants
  matching `status` and `search`. Reads `master.tenants` only, like the list.

### `search` (PLAT-04)

Optional. A trimmed, case-insensitive "contains" match on `slug`, `displayName` or `ownerEmail` (any of the three), combined
with `status` by AND. Blank or whitespace-only means no search. Longer than **100 characters** (after trimming) is a `400`
(`search must be at most 100 characters`), never silently truncated. Control characters (NUL, newline, ...) are a `400` (`search contains invalid characters`). `%`, `_` and `\` are matched literally (escaped; `100%`
does not match everything and `a_c` does not match `abc`), quotes and any other character are plain data, and the term is a
bound query parameter, never concatenated into SQL. The response envelope is unchanged (`totalElements` counts the matches).

## Plan and subscription fields (PLAN-03)

Contract for PLAN-01 (AC11); full plan API in [plans.md](plans.md). Every tenant summary (list rows, detail, the `tenant`
of an action response, and the answer of `PUT /api/v1/platform/tenants/{slug}/plan`) carries:

| key | type | notes |
|---|---|---|
| `plan` | string | **the plan code** (`STARTER`, `GROWTH`, `SCALE`, or any code a super-admin created; `^[A-Z][A-Z0-9_]{1,31}$`). Still a string, so the console's existing parser (string <= 40) keeps working. Before PLAN-01 it was `FREE`, `STARTER` or `PRO`; the V51 backfill maps FREE and STARTER to STARTER, PRO to SCALE |
| `planName` | string | the plan's current `name` (display) |
| `subscriptionStatus` | string | `TRIALING`, `ACTIVE`, `PAST_DUE`, `CANCELED` |
| `trialEndsAt` | string \| null | instant ending in `Z` (like audit `occurredAt`, unlike `createdAt`/`updatedAt` which have no offset); `null` while the tenant has not finished provisioning. A `TRIALING` tenant whose `trialEndsAt` is in the past is read-only for paid features ([plans.md](plans.md) section 13); the console may badge it "trial ended" |

The JSON examples above and below that are tagged `example:` / `plat04:` are parsed into the CURRENT Java records by
`PlatformTenantsContractTest` / `PlatformReadContractTest` (unknown keys fail, and since PLAN-01 every tenant object must
carry `plan`, `planName`, `subscriptionStatus` and `trialEndsAt`). The PLAN-03 examples of the new shape (validated
against `plans.schema.json` by `PlansDocSchemaTest`, and their key sets against live responses by `PlansDocContractTest`):

<!-- example: tenant-list-with-plan response TenantPageEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "items": [
      {
        "id": 12,
        "slug": "acme",
        "displayName": "Acme Perfumes",
        "status": "ACTIVE",
        "plan": "GROWTH",
        "planName": "Growth",
        "subscriptionStatus": "TRIALING",
        "trialEndsAt": "2026-12-11T09:30:00Z",
        "ownerEmail": "owner@acme.example",
        "customDomain": null,
        "customDomainStatus": null,
        "createdAt": "2026-11-01T09:30:00.123456",
        "updatedAt": "2026-11-27T09:31:10.654321"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "sort": "createdAt",
    "direction": "desc",
    "customDomainCounts": {"PENDING": 0, "VERIFIED": 0, "FAILED": 0, "NONE": 1}
  }
}
```

<!-- example: tenant-detail-with-plan response TenantSummaryEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "id": 12,
    "slug": "acme",
    "displayName": "Acme Perfumes",
    "status": "ACTIVE",
    "plan": "GROWTH",
    "planName": "Growth",
    "subscriptionStatus": "TRIALING",
    "trialEndsAt": "2026-12-11T09:30:00Z",
    "ownerEmail": "owner@acme.example",
    "customDomain": null,
    "customDomainStatus": null,
    "createdAt": "2026-11-01T09:30:00.123456",
    "updatedAt": "2026-11-27T09:31:10.654321"
  }
}
```

The plan action `PUT /api/v1/platform/tenants/{slug}/plan` (set plan, extend trial, set `ACTIVE`/`CANCELED`) is
documented in [plans.md](plans.md) section 11. It writes a tenant audit row with `action` `CHANGE_PLAN`, `phase`
`OUTCOME` and `previousStatus` = `newStatus` (the registry status does not change); the plan details are in the plan
audit log. A trial cannot be set before it has started: `trialEndsAt` on a tenant whose `trialEndsAt` is still `null`
(not provisioned yet) is `400 {"data": {"code": "TRIAL_NOT_STARTED"}}`. The list has no plan filter yet.

## Billing fields (BILL-00)

Contract only; **BILL-01b** implements them. Every tenant summary object (list rows, detail, the `tenant` of an action response and the answer of `PUT /api/v1/platform/tenants/{slug}/plan`) gains five keys, derived from the subscription state of [billing.md](billing.md) section 3 (booleans and enums only, never card data):

| key | type | notes |
|---|---|---|
| `billingStatus` | string | `lifecycleStatus`: `TRIALING`, `ACTIVE`, `PAST_DUE`, `CANCELED`, `EXPIRED`. `subscriptionStatus` above stays the 4-value projection (`EXPIRED` projects to `CANCELED`), so existing console code keeps working |
| `accessState` | string | `FULL`, `GRACE`, `READ_ONLY` (billing restriction; unrelated to the tenant `status` `SUSPENDED`, which stays the whole-store lock) |
| `paymentStatus` | string | `NONE`, `CURRENT`, `PENDING`, `RETRYING`, `FAILED` |
| `cardOnFile` | boolean | an active authorization exists |
| `billingMode` | string | `PAYSTACK` (charged by the billing engine) or `MANUAL` (existing tenants and admin-activated tenants: not charged; the PLAN-01 status bridge applies) |

The fragments `tenant-billing-fields` and `tenant-billing-fields-grace` in billing.md section 3.4 show the keys. **The two PLAN-03 examples above (`tenant-list-with-plan`, `tenant-detail-with-plan`) and the `example:` / `plat04:` blocks are parsed into the CURRENT records and compared with live responses key for key by `PlatformTenantsContractTest`, `PlatformReadContractTest` and `PlansDocContractTest`, so they are NOT changed here.** BILL-01b adds the five keys to the DTOs, the two PLAN-03 examples, `TenantSummary` in `plans.schema.json` and the console parsers in ONE PR. A tenant without a subscription row (never converted) reports `billingStatus` from the legacy `subscriptionStatus`, `accessState` `FULL` (or `READ_ONLY` by the pre-billing rule), `paymentStatus` `NONE`, `cardOnFile` false, `billingMode` `MANUAL`. The console list gets no billing filter here; filters for subscriptions live on `GET /api/v1/platform/billing/subscriptions` (billing.md section 11.1).

The PLAN-01 bridge `PUT /api/v1/platform/tenants/{slug}/plan` answers `409` with `data.code` `BILLING_MANAGED` for a tenant with `billingMode PAYSTACK` and a live subscription (use the billing actions, billing.md section 7.11); for `MANUAL` tenants it is unchanged and is routed through the subscription state service and audited as a manual change.

## GET /api/v1/platform/tenants/{slug} (PLAT-04)

Tenant detail. `{slug}` rules as above (unknown or malformed: `404 {"description": "Tenant not found", "data": null}`).
`data` is exactly one list row (same keys, same reading rules: `ownerEmail` is personal data for SUPER_ADMIN only;
`schemaName`, `webhookToken` and tenant-schema data are never returned). Read-only, writes nothing.

```json plat04:detail-response
{
  "description": null,
  "data": {
    "id": 12,
    "slug": "acme",
    "displayName": "Acme Perfumes",
    "status": "ACTIVE",
    "plan": "STARTER",
    "planName": "Starter",
    "subscriptionStatus": "TRIALING",
    "trialEndsAt": "2026-11-15T09:31:10.654321Z",
    "ownerEmail": "owner@acme.example",
    "customDomain": null,
    "customDomainStatus": null,
    "createdAt": "2026-11-01T09:30:00.123456",
    "updatedAt": "2026-11-01T09:31:10.654321"
  }
}
```

## GET /api/v1/platform/tenants/{slug}/audit (PLAT-04)

The rows of `master.tenant_audit_log` for one tenant, **newest first** (`id` descending), paged. Query: `page` (0-based,
default 0), `size` (default 20, capped at 100, response reports the effective size), `sort` (`id` default, `occurredAt`),
`direction` (`desc` default, `asc`); the unique `id` is always the final tiebreaker. `page` is capped at **1,000,000**: a larger
value (or a negative one) is `400 {"description": "page must be <= 1000000", ...}`, on the list too. Bad `page`/`size`/`sort`/`direction`: `400`
like the list. Unknown or malformed slug: the same `404` as the detail. A tenant with no rows returns an empty `items`.
The rows are looked up by the tenant's id (not its slug), so a slug reused after a purge starts with an empty history and never
inherits the rows of the purged tenant. A purged tenant (registry row deleted) answers `404` even though its audit rows survive
in the table.

Row keys (exactly these eight; the console allow-list `parseAuditRows` keeps `id, occurredAt, actorEmail, action,
previousStatus, newStatus, phase` and drops the rest):

| key | type | notes |
|---|---|---|
| `id` | number | per-table identity, strictly increasing: the order the rows were written |
| `tenantSlug` | string | the requested tenant |
| `action` | string | `APPROVE`, `SUSPEND`, `REACTIVATE`, `REPROVISION`; from PLAN-01 also `CHANGE_PLAN` (plan, trial or subscription status changed, see "Plan and subscription fields") |
| `phase` | string | `INTENT`, `OUTCOME`, `RECLAIM`, `RECONCILE` (meaning in the previous section) |
| `actorEmail` | string | platform admin who acted (also on RECLAIM and RECONCILE); SUPER_ADMIN data, do not log or cache in shared storage |
| `previousStatus`, `newStatus` | string | tenant statuses |
| `occurredAt` | string | database time, UTC, always ending in `Z`. The fractional part has **variable length** (trailing zeros are trimmed) and may be absent: `2026-01-01T00:00:00Z`, `2026-01-01T00:00:00.5Z`, `2026-11-14T10:00:00.123456Z` are all valid. |

Never returned: the internal admin id, the tenant id, password hashes, webhook tokens, schema names or any payload (the table
holds none). The reading rules above are unchanged and apply to the rows ordered by `id` ascending: when paging newest first,
reverse the page before applying them, and fetch older pages when an `INTENT`/`OUTCOME` pair straddles a page boundary.
Timestamps: the tenant fields `createdAt` and `updatedAt` are `LocalDateTime` strings with **no offset** (server local time),
while audit `occurredAt` is an instant ending in `Z`. They are different formats and must be parsed separately; never compare
them directly.

Paging caveats for clients: offset paging newest first can **repeat** a row (or shift one) if a new row is inserted between two
fetches, so de-duplicate by `id` when stitching pages. To apply the reading rules to a trail that spans several pages, either
load all pages up to a cap, or load contiguous pages starting from the newest end and judge only rows that are away from the
oldest loaded boundary: an `INTENT` whose `OUTCOME`/`RECLAIM`/`RECONCILE` may lie on the next (older or newer) page must stay
"undetermined" until that page is loaded. Never judge an `INTENT`/`OUTCOME` pair that straddles the page boundary.

Deviation from the console's current parser input: it expects a bare array of rows; here the array is `data.items` inside the
standard paging envelope (`page`, `size`, `totalElements`, `totalPages`, `sort`, `direction`), and rows carry an extra `tenantSlug`.

```json plat04:audit-response
{
  "description": null,
  "data": {
    "items": [
      {
        "id": 42,
        "tenantSlug": "acme",
        "action": "REPROVISION",
        "phase": "OUTCOME",
        "actorEmail": "root@platform.example",
        "previousStatus": "FAILED",
        "newStatus": "ACTIVE",
        "occurredAt": "2026-11-14T10:00:05.250000Z"
      },
      {
        "id": 41,
        "tenantSlug": "acme",
        "action": "REPROVISION",
        "phase": "INTENT",
        "actorEmail": "root@platform.example",
        "previousStatus": "FAILED",
        "newStatus": "PROVISIONING",
        "occurredAt": "2026-11-14T10:00:00.100000Z"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 2,
    "totalPages": 1,
    "sort": "id",
    "direction": "desc"
  }
}
```

Access and limits of the three read endpoints: SUPER_ADMIN only (class-level `@PreAuthorize` plus the `/platform/**` matcher),
platform cookie required, same errors as above. They run in read-only transactions, never write an audit row and have no
per-admin throttle (the `429` action throttle covers the four POST actions and, from PLAN-01, the plan action `PUT /tenants/{slug}/plan`).

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
      "plan": "STARTER",
      "planName": "Starter",
      "subscriptionStatus": "TRIALING",
      "trialEndsAt": "2026-11-15T09:31:10.654321Z",
      "ownerEmail": "owner@acme.example",
      "customDomain": null,
      "customDomainStatus": null,
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
      "plan": "STARTER",
      "planName": "Starter",
      "subscriptionStatus": "TRIALING",
      "trialEndsAt": "2026-11-15T09:31:10.654321Z",
      "ownerEmail": "owner@acme.example",
      "customDomain": null,
      "customDomainStatus": null,
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
      "plan": "STARTER",
      "planName": "Starter",
      "subscriptionStatus": "TRIALING",
      "trialEndsAt": null,
      "ownerEmail": "owner@bravo.example",
      "customDomain": null,
      "customDomainStatus": null,
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
table holds no passwords, hashes, tokens or payloads. It is read through `GET /tenants/{slug}/audit` (PLAT-04).

How a console (or an auditor with SQL access) should read it, per tenant ordered by `id`:

* `INTENT` followed by `OUTCOME` (same action): completed; `new_status` of the OUTCOME is the result (`FAILED` means the saga
  failed and the caller received the generic 500).
* `INTENT` with no later `OUTCOME` or `RECONCILE`: the action was accepted but its end was not recorded (crash, or the audit
  write failed). The tenant registry status is authoritative: `ACTIVE` means the saga completed, `FAILED` it did not,
  `PROVISIONING` it is running or dead (a reprovision reclaims it after the stuck window, with a RECLAIM row).
  A retried approve on an `ACTIVE` tenant older than 60 s (`platform.tenants.reconcile-grace-seconds`) closes the gap with a
  RECONCILE row. Never infer that nothing happened because the OUTCOME is missing.
* A `RECLAIM` row sitting between two `INTENT` rows of a tenant marks the EARLIER `INTENT` as dead: that saga died while
  `PROVISIONING` and was moved to `FAILED`; the later `INTENT` belongs to the reprovision that followed. Do not read the earlier
  INTENT as an action still in progress, and do not expect an `OUTCOME` for it.
* `RECONCILE` rows attribute the repair to the admin who retried, not to the original actor: the original actor is the one on
  the INTENT row.
* Reprovision of an `ACTIVE` tenant is a 409 (illegal), so only `approve` retries reconcile.

The signup password hash is deleted in its own transaction right after the saga commits, independent of the audit insert; if
that fails the hash is deleted by the reconcile step of the next approve retry (nothing is lost, and a leftover hash is only a
BCrypt hash for a tenant whose owner already exists).

## Deployment notes (PLAT-02b)

* **What the audit triggers do and do not protect (known limitation).** The V6 triggers reject `UPDATE`, `DELETE` and
  `TRUNCATE` on `master.tenant_audit_log` for ANY SQL, including SQL issued by the application, so a bug or an injected
  statement running as the application cannot rewrite history. They do NOT protect against the table owner or a superuser: an
  owner can `DROP TRIGGER` or `ALTER TABLE ... DISABLE TRIGGER` and then change rows. Today the application uses a single
  database role for everything (Flyway migrations at boot via `TenantSchemaMigrator`/`TenantFlywayInitializer`, tenant
  `CREATE SCHEMA`/`DROP SCHEMA` DDL in the provisioning saga, and normal queries), so that role is necessarily the owner
  and **the tamper resistance is NOT enforced against a compromised application credential or database administrator**.
  Splitting migrations/DDL from a runtime role with only `INSERT, SELECT` on `tenant_audit_log` is the intended hardening, but
  it is NOT deliverable with the current code and must not be assumed in deployment; it is tracked as a separate security
  item (SEC-01c). Until then, protect the audit trail with database-side controls (restricted access to the credential,
  database audit logging, off-host log shipping).
* **Single instance.** After every action the CORS decision cache is dropped with `TenantOriginPolicy.invalidateAll()`, which
  clears the cache of the JVM that handled the request only. With more than one API instance the other instances converge only
  when their cached decision expires: the cache TTL (30 s allowed, 10 s denied) plus, under registry-lookup pressure, the
  stale-allow grace of 120 s (DOMAIN-01c), so a suspended tenant's origin can still be allowed on another instance for up to
  about 150 s. Tenant resolution itself (the 423 for a suspended tenant) reads the registry on every request and is not
  affected. Run a single instance, or add a cluster-wide invalidation (for example Redis pub/sub) before scaling out.

## Examples

```bash
curl -b cookies.txt -H 'Origin: https://console.shopfi.example' \
  'https://api.shopfi.example/api/v1/platform/tenants?status=FAILED&size=50&sort=slug&direction=asc'

curl -b cookies.txt -X POST -H 'Origin: https://console.shopfi.example' \
  https://api.shopfi.example/api/v1/platform/tenants/bravo/reprovision
```
