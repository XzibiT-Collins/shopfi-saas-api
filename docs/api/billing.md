# Subscription billing API (BILL-00)

Status: **contract, docs only. Implemented by BILL-01, BILL-01b, BILL-01c, BILL-02, BILL-03, BILL-04, BILL-05, BILL-06, BILL-07 and consumed by BILL-FE-01, BILL-FE-01b, BILL-FE-02a, BILL-FE-02b, BILL-FE-03 and BILL-RS.**
Owner PRD: `.keep/billing-keep/PRD.md` (FR-01..26, BR-01..10; cited as FR-nn / BR-nn below). Board: "Billing wave 2026-11-28". Owner decisions D1-D5 (2026-11-28) and the
PO decisions of the BILL-00 brief are authoritative; owner questions O1-O30 are assumed at their stated defaults, and O2 (card only), O18 (PDF library approved) and O1 (GHS 1.00 hold) were answered by the owner on 2026-11-28 and are FINAL (section 20); only O9 (tax treatment) is still pending and is written so that either answer is a settings change (section 20.4).
A deviation from this document is a change to this document first (same rule as [plans.md](plans.md)).

| Artifact | Path |
|---|---|
| This document | `backend/docs/api/billing.md` |
| JSON Schema (draft 2020-12) for every request and response body | `backend/docs/api/billing.schema.json` |
| Docs checker (schema validation of every example + money recomputation) | `.keep/bill00-keep/check_examples.py` (see section 21) |
| Frontend fixtures (the examples, by name) | `.keep/bill00-keep/billing-docs-examples.json`; the frontend copies them to `src/lib/billing/fixtures/` and `src/lib/platform/billing/fixtures/` |
| Plans, entitlements, `readOnly`, 402, caching | [plans.md](plans.md) (sections 13-15, 18); the price-change wording of PLAN-03 AC6 is superseded here (section 7.8, [plans.md](plans.md) section 15) |
| Signup (the card step is additive) | [onboarding.md](onboarding.md) section 3b |
| Tenant summary billing keys | [platform-tenants.md](platform-tenants.md#billing-fields-bill-00) |
| Error codes | [errors.md](errors.md#subscription-billing-bill-00) |
| Routes and roles | [authorization-matrix.md](authorization-matrix.md#3c-subscription-billing-bill-00-contract) |
| Notification catalogue | [notifications.md](notifications.md#subscription-billing-notifications-bill-00) and section 16 below |

Every example is tagged `<!-- example: <name> <kind> <Schema> [status] -->`, the grammar of [plans.md](plans.md) and [onboarding.md](onboarding.md): `valid` request examples
must validate against `billing.schema.json` and be accepted by the server, `invalid` request examples must fail the schema (or, where the rule is not
expressible in JSON Schema, are listed with the server rule) and be rejected with the stated status, and `response` examples must validate against the
named `$defs` entry and equal what the server returns for the named scenario (description, data keys, and data values where deterministic).
A `<!-- fragment: <name> <Schema> -->` block is a part of a body (the keys added to an existing object) and is validated against its schema only.
The implementing tickets write `BillingDocExamples` / `BillingDocSchemaTest` / `BillingDocContractTest` with this grammar; until then
`.keep/bill00-keep/check_examples.py` is the gate (it also recomputes every money figure of the tables and examples).
All examples tell ONE story (store `acme`, tenant id 12, owner `owner@acme.example`, environment LIVE) so the numbers agree across examples; the dates and amounts are
illustrative, the formulas are normative.

## 1. Conventions

- Base paths: member `/api/v1/admin/billing/**` (tenant host, cookie or bearer of the tenant), platform console `/api/v1/platform/billing/**` (platform host, `platformAccessToken`),
  public signup additions under `/api/v1/platform/onboarding/**`, the Paystack receiver `/api/v1/platform/billing/webhooks/paystack/{environment}`, and the dev-only
  `/api/v1/dev/billing/**`. Platform endpoints follow [platform-tenants.md](platform-tenants.md) (cookie auth, platform CORS allow-list as the CSRF guard, tenant tokens of any role are `401`).
- JSON in, JSON out, camelCase, every body the envelope `{ "description": string|null, "data": T|null }` ([plans.md](plans.md) section 1). Success: `description: null`.
  Errors: branch on the HTTP status and `data.code`, never on `description`. Request bodies are `additionalProperties: false` in the schema; the server ignores unknown properties on
  write like the rest of the API, so do not send them. Plain-text fields follow [plans.md](plans.md) section 1 (Cc, Cf, U+2028/2029 rejected, trimmed; blank optional text is stored as `null`).
- **Money** is integer pesewas (`long`, never `int`, never a float, never a string), `currency` is always `"GHS"`; intermediates are `long` or `BigDecimal` with `RoundingMode.HALF_UP`.
  **Every derived value is computed on the server only** (proration, charge amounts, tax split, refundable remaining, MRR, ...). A client formats; it never does money arithmetic and never sends a
  price or an amount a charge is made from (a charge is computed from server state; the only client money input is a refund amount, bounded by the server).
- **Timestamps** are UTC instants ending in `Z` (`Timestamp` in the schema, same as [plans.md](plans.md)). Period boundaries and every schedule are computed in UTC (section 6.2). The server clock is the injectable `Clock` bean;
  billing computes in UTC explicitly and stores `timestamptz`.
- **Environments.** `TEST` and `LIVE`. In a URL path the environment is lower case (`test`, `live`); in a body it is the enum `TEST` / `LIVE`. Exactly one environment is active (`activeEnvironment`). A subscription,
  authorization, payment and webhook event carries its environment; test records are never charged, reported or mixed into LIVE (O17).
- **References.** Every Paystack charge uses a deterministic, unique reference built from our own state (S4): `sub-<t|l>-<tenantId>-<kind>-<n>-<attempt>` where `kind` is `tc` trial conversion, `rn` renewal or
  retry of a renewal, `up` upgrade, `sb` first charge of a new subscription after the previous one ended, `mn` manual; `n` is the billing period index (`tc` is `0`, the first renewal `1`, ...; `up`: the per-subscription change sequence; `sb`: the subscription generation) and `attempt` starts at 1.
  Card verification holds use `sub-card-<t|l>-<24 lower-case alphanumerics>`. Hyphens only: Paystack accepts only letters, digits, `-`, `.` and `=` in a reference (the board proposed `sub_card_<random>`; underscores are replaced). A reference never contains a slug or an e-mail.
  Purpose of a payment row: `TRIAL_CONVERSION` (`tc`), `RENEWAL` (`rn` attempt 1, and `sb`), `RETRY` (`rn` attempt >= 2), `UPGRADE` (`up`), `CARD_VERIFY` (`sub-card-`), `MANUAL` (`mn`).
- **Idempotency-Key** (header) on every money-moving member and admin POST (`changes`, `subscribe`, refund): 8-64 characters of `A-Za-z0-9._:-` (same format as [walk-in.md](walk-in.md)); missing `400 IDEMPOTENCY_KEY_REQUIRED`,
  malformed `400 IDEMPOTENCY_KEY_INVALID`; the same key with the same body replays the stored first answer (same status and body, no second charge, no second audit row); the same key with a different body `409 IDEMPOTENCY_KEY_REUSED`. Keys are scoped to (tenant or admin, endpoint) and kept 24 h.
- **Paging** (`page` zero-based, `size` 1..100, default 20): list bodies carry `items`, `page`, `size`, `totalElements`, `totalPages` (the plans / tenants shape). Lists are newest first unless stated.
- **Optimistic locking.** `version` integers (`SubscriptionDetail.version`, `BillingSummary.version`, settings `version`) are the lock input; a stale value is `409` with `data.code` `SUBSCRIPTION_VERSION_CONFLICT`, `BILLING_SETTINGS_VERSION_CONFLICT` or `CREDENTIALS_VERSION_CONFLICT` and `data.currentVersion`, nothing written (never the generic `503 TRY_AGAIN` answer).
- **Rate limits**: a throttled request is `429`, `Retry-After: <whole seconds, >= 1>`, `{"description": "Too many requests. Please try again later.", "data": null}`; the keys and defaults are in section 18.
- **Never 402.** No billing endpoint ever answers `402`; `402` stays the plan-gate answer of [plans.md](plans.md) section 14 on feature routes. Billing endpoints are never plan-gated and work while the tenant is `READ_ONLY` (section 17.2).

## 2. Endpoints at a glance

Roles: "ADMIN" = tenant `hasRole('ADMIN')`; "FRONT_DESK" = `hasAnyRole('ADMIN','FRONT_DESK')`; "SUPER_ADMIN" = `hasRole('SUPER_ADMIN')` on the platform host (class-level `@PreAuthorize` plus the `SecurityConfig` matcher, like [plans.md](plans.md) section 18 item 1).

**Public (signup, section 5)**

| Endpoint | Auth | Success | Purpose |
|---|---|---|---|
| `POST /api/v1/platform/onboarding/card/initialize` | public, header `X-Card-Setup-Token` | 200 | start the hosted card authorization for a signup |
| `GET /api/v1/platform/onboarding/card/callback-verify?reference=` | public, header `X-Card-Setup-Token` | 200 | verify the authorization server side, store the card, continue provisioning |

**Member (tenant host, section 9)**

| Endpoint | Auth | Success | Purpose |
|---|---|---|---|
| `GET /api/v1/admin/billing/summary` | FRONT_DESK (ADMIN: full, FRONT_DESK: banner subset) | 200 | subscription, the three states, next charge, card, pending change, banners, capability flags |
| `GET /api/v1/admin/billing/card` | ADMIN | 200 | display data of the default card |
| `POST /api/v1/admin/billing/card/initialize` | ADMIN | 200 | start a hosted card authorization (add or replace the card) |
| `POST /api/v1/admin/billing/card/verify` | ADMIN | 200 | verify it, replace the default card, retry a due payment once |
| `POST /api/v1/admin/billing/retry-payment` | ADMIN | 202 | one charge attempt now with the card on file |
| `POST /api/v1/admin/billing/subscribe` | ADMIN, `Idempotency-Key` | 200 / 202 | start a new subscription after the previous one ended (charged now, no trial) |
| `POST /api/v1/admin/billing/changes/preview` | ADMIN | 200 | classify a plan or interval change and show the exact charge (no state change) |
| `POST /api/v1/admin/billing/changes` | ADMIN, `Idempotency-Key` | 200 / 202 | apply a previewed change |
| `POST /api/v1/admin/billing/cancel` | ADMIN | 200 | schedule non-renewal |
| `POST /api/v1/admin/billing/reactivate` | ADMIN | 200 | undo a scheduled cancellation before the period ends |
| `GET /api/v1/admin/billing/payments` | ADMIN | 200 | billing history, paged |
| `GET /api/v1/admin/billing/payments/{id}` | ADMIN | 200 | one attempt (polling a pending charge) |
| `GET /api/v1/admin/billing/documents` | ADMIN | 200 | invoices, receipts, credit notes, paged (`?type=`) |
| `GET /api/v1/admin/billing/documents/{id}` | ADMIN | 200 | one document (JSON) |
| `GET /api/v1/admin/billing/documents/{id}/pdf` | ADMIN | 200 | the PDF |
| `POST /api/v1/admin/billing/documents/{id}/resend` | ADMIN | 202 | e-mail the document link again |

**Platform console (SUPER_ADMIN, sections 4 and 11-14)**

| Endpoint | Success | Purpose |
|---|---|---|
| `GET` / `PUT /api/v1/platform/billing/settings` | 200 | billing settings (one versioned row) |
| `GET /api/v1/platform/billing/settings/audit` | 200 | settings audit, paged |
| `GET /api/v1/platform/billing/credentials` | 200 | status of both environments (never the keys) |
| `PUT /api/v1/platform/billing/credentials/{environment}` | 200 | write the keys of one environment (write-only) |
| `POST /api/v1/platform/billing/credentials/{environment}/verify` | 200 | test the keys with Paystack |
| `GET /api/v1/platform/billing/subscriptions` | 200 | all subscriptions, filters, paged |
| `GET /api/v1/platform/billing/subscriptions/{slug}` | 200 | detail incl. timeline, payments, documents, adjustments, audit |
| `POST /api/v1/platform/billing/subscriptions/{slug}/actions` | 200 | manual actions (activate, restrict, restore, extend trial, change plan, cancel, retry, clear pending change) |
| `POST` / `DELETE /api/v1/platform/billing/subscriptions/{slug}/complimentary` | 200 | grant / end a complimentary plan |
| `GET /api/v1/platform/billing/subscriptions/{slug}/history` | 200 | merged billing history |
| `POST /api/v1/platform/billing/payments/{id}/refund` | 200 / 202 | refund (money only), `Idempotency-Key` |
| `GET /api/v1/platform/billing/audit`, `GET /api/v1/platform/tenants/{slug}/billing-audit` | 200 | billing audit, paged |
| `GET /api/v1/platform/billing/documents`, `GET .../documents/{id}`, `GET .../documents/{id}/pdf`, `POST .../documents/{id}/resend` | 200 / 202 | documents of every tenant |
| `GET /api/v1/platform/billing/reconciliation/findings`, `POST .../findings/{id}/resolve`, `POST .../reconciliation/run`, `GET .../reconciliation/runs` | 200 / 202 | reconciliation |
| `GET /api/v1/platform/billing/webhook-events`, `POST .../webhook-events/{id}/retry` | 200 / 202 | webhook event health |
| `GET /api/v1/platform/billing/reports/summary`, `.../timeseries`, `.../export.csv` | 200 | reports |

**Receiver and dev**

| Endpoint | Auth | Success | Purpose |
|---|---|---|---|
| `POST /api/v1/platform/billing/webhooks/paystack/{environment}` | none (HMAC-SHA512 signature) | 200 | Paystack events (section 12) |
| `GET /api/v1/dev/billing/clock`, `POST .../clock/advance`, `POST .../run` | dev profile only | 200 | controllable billing clock and a billing round for real-stack e2e (section 19) |

There is no delete of a subscription, payment, document or adjustment anywhere (append-only, BR-08).

## 3. State model (FR-05, 4.4, BR-01)

A subscription is described by **three independent fields** (plus two flags and a mode). They are stored together in the master table `tenant_subscriptions`, written by exactly one
service (`SubscriptionStateService`) and never derived from each other in a client.

| Field | Values | Meaning |
|---|---|---|
| `lifecycleStatus` | `TRIALING`, `ACTIVE`, `PAST_DUE`, `CANCELED`, `EXPIRED` | where the subscription is in its life. `TRIALING`: inside the trial (card on file, nothing charged yet). `ACTIVE`: paid (or complimentary / manually activated) and running. `PAST_DUE`: a charge failed and is not settled. `CANCELED`: ended because a cancellation reached its end (period or trial end, grace end, or an immediate admin cancel). `EXPIRED`: ended because a fixed term ran out with nothing renewing it (today only an admin `ACTIVATE` with `until`, section 11.3). Both ended states are terminal until a new subscription is started (section 7.5) |
| `cancelAtPeriodEnd` | boolean | non-renewal is scheduled (BR-05). It changes nothing about access or payment now |
| `complimentary` | boolean | the plan is granted free (section 7.9). Billing is paused |
| `paymentStatus` | `NONE`, `CURRENT`, `PENDING`, `RETRYING`, `FAILED` | the money side. `NONE`: nothing is owed or expected (complimentary, ended, manual). `CURRENT`: nothing overdue. `PENDING`: a charge is in flight and its outcome is not known yet (resolved by `verify`, never by charging again). `RETRYING`: the last charge failed and further attempts are scheduled. `FAILED`: it failed and no attempt is scheduled (retries used up, no card, or grace over) |
| `accessState` | `FULL`, `GRACE`, `READ_ONLY` | what the tenant may do. `FULL`: everything the plan allows. `GRACE`: still everything (reminders are shown). `READ_ONLY`: paid features are read-only exactly as [plans.md](plans.md) section 13 / 14 (`readOnly` is true, gated writes answer `402 SUBSCRIPTION_INACTIVE`); the storefront, checkout, orders and payments of the shop keep working |
| `billingMode` | `PAYSTACK`, `MANUAL` | `PAYSTACK`: charged by the billing engine. `MANUAL`: not charged (existing tenants before they add a card, admin-activated tenants); the PLAN-01 `PUT /platform/tenants/{slug}/plan` bridge applies (O4, O22) |

### 3.1 Invariants (a pure function enforces them; a unit test per line)

1. `accessState = GRACE` implies `lifecycleStatus = PAST_DUE`. `lifecycleStatus` `CANCELED` or `EXPIRED` implies `accessState = READ_ONLY` and `paymentStatus = NONE`.
2. `complimentary = true` implies `lifecycleStatus = ACTIVE`, `paymentStatus = NONE`, `accessState = FULL`, `cancelAtPeriodEnd = false`.
3. `lifecycleStatus = PAST_DUE` implies `paymentStatus` is `RETRYING`, `FAILED` or `PENDING`. `TRIALING` implies `CURRENT`, `NONE` (manual mode) or `PENDING`.
4. `cancelAtPeriodEnd = true` implies the lifecycle is `TRIALING`, `ACTIVE` or `PAST_DUE`.
5. At most ONE payment of a subscription is in flight (`INITIATED` or `PENDING`) at any time (S4); `paymentStatus = PENDING` is exactly that.
6. A subscription row exists for a tenant only while it is billing-managed or was converted; `MANUAL` tenants without a subscription row read as `lifecycleStatus` from the legacy projection (section 3.4).

### 3.2 Events and the legal transition table

The state is changed only by these events, each through `SubscriptionStateService`, in ONE transaction with the audit row, the `tenants` projection and the `TenantSubscriptionChangedEvent` (cache eviction after commit, section 17.4).
An event whose source state is not listed is an `IllegalBillingTransitionException`: nothing is written (`409 ACTION_NOT_ALLOWED` on an admin action, an alert and no change in a job). The exhaustive unit test runs every (state, event) pair.

| # | Event | Raised by | From (`lifecycleStatus`) | To `lifecycleStatus` | `paymentStatus` | `accessState` |
|---|---|---|---|---|---|---|
| E1 | `TRIAL_STARTED` | provisioning complete with a card (BILL-02) | none | `TRIALING` | `CURRENT` | `FULL` |
| E2 | `TRIAL_CHARGE_SUCCEEDED` | billing job / webhook (verified) | `TRIALING` | `ACTIVE` | `CURRENT` | `FULL` |
| E3 | `CHARGE_STARTED` | any charge about to be sent | `TRIALING`, `ACTIVE`, `PAST_DUE` | unchanged | `PENDING` | unchanged |
| E4 | `RENEWAL_SUCCEEDED` | billing job / webhook (verified) | `ACTIVE` | `ACTIVE` | `CURRENT` | `FULL` (unless E15 applies) |
| E5 | `CHARGE_FAILED` (first charge or renewal, attempt 1) | billing job / webhook (verified) | `TRIALING`, `ACTIVE` | `PAST_DUE` | `RETRYING` if a retry is scheduled, else `FAILED` | `GRACE` when `graceDays > 0`, else `READ_ONLY` |
| E6 | `RETRY_FAILED` | retry job / webhook | `PAST_DUE` (access `GRACE`) | `PAST_DUE` | `RETRYING` if another attempt is scheduled, else `FAILED` | `GRACE` |
| E7 | `GRACE_EXPIRED` | grace expiry job | `PAST_DUE` (access `GRACE`) | `PAST_DUE`, or `CANCELED` when `cancelAtPeriodEnd` (O25) | `FAILED`, or `NONE` when ended | `READ_ONLY` |
| E8 | `PAYMENT_SETTLED` | any verified success while `PAST_DUE`: scheduled retry, member retry, webhook, reconciliation, admin | `PAST_DUE` | `ACTIVE` | `CURRENT` | `FULL` (unless E15 applies) |
| E9 | `CANCEL_REQUESTED` | member `cancel`, admin `CANCEL_AT_PERIOD_END` | `TRIALING`, `ACTIVE`, `PAST_DUE` | unchanged, `cancelAtPeriodEnd = true` | unchanged | unchanged |
| E10 | `CANCEL_REVERSED` | member `reactivate`, admin | as E9 with the flag set | unchanged, flag `false` | unchanged | unchanged |
| E11 | `CANCEL_EFFECTIVE` | job at period end / trial end (flag set), E7 with the flag, admin `CANCEL_NOW` | `TRIALING`, `ACTIVE`, `PAST_DUE` | `CANCELED` | `NONE` | `READ_ONLY` |
| E12 | `TERM_ENDED` | job, admin `ACTIVATE` with `until` elapsed | `ACTIVE` (manual term) | `EXPIRED` | `NONE` | `READ_ONLY` |
| E13 | `RESUBSCRIBED` | verified success of an `sb` charge | `CANCELED`, `EXPIRED`, or `ACTIVE` in `MANUAL` mode | `ACTIVE` | `CURRENT` | `FULL` |
| E14 | `PLAN_CHANGED` | verified upgrade, scheduled change applied at renewal, admin `CHANGE_PLAN` | `TRIALING`, `ACTIVE` (admin: also `PAST_DUE`) | unchanged | unchanged | unchanged |
| E15 | `ACCESS_RESTRICTED_MANUAL` / `ACCESS_RESTORED_MANUAL` | admin `RESTRICT` / `RESTORE` | `TRIALING`, `ACTIVE`, `PAST_DUE` | unchanged | unchanged | `RESTRICT`: `READ_ONLY` and `manualRestriction = true`; `RESTORE`: clears the flag and recomputes the access from the lifecycle and grace rules (a billing restriction after grace stays) |
| E16 | `COMPLIMENTARY_GRANTED` | admin | any live or ended state | `ACTIVE`, `complimentary = true` | `NONE` | `FULL` |
| E17 | `COMPLIMENTARY_ENDED` | admin or job at `until` | `ACTIVE` with `complimentary` | with a card: `ACTIVE`, due at once (a renewal charge at the end instant, anchor = end instant); without a card: `PAST_DUE` with reason `NO_CARD` | `CURRENT` then per the charge; `FAILED` for `NO_CARD` | `FULL`; `GRACE` for `NO_CARD` |
| E18 | `TRIAL_EXTENDED` | admin `EXTEND_TRIAL` | `TRIALING` | `TRIALING` | unchanged | unchanged |
| E19 | `CARD_ATTACHED` | member card verify while `billingMode = MANUAL` | `TRIALING`, `ACTIVE` | unchanged, `billingMode = PAYSTACK` | `NONE` -> `CURRENT` | unchanged |
| E20 | `TRIAL_NO_CARD_REACHED` | job at `trialEndsAt` for a `MANUAL` tenant without a card (O4) | `TRIALING` (`MANUAL`) | `PAST_DUE`, reason `NO_CARD` | `FAILED` | `GRACE` |

Diagram (lifecycle only; the other two fields move as in the table):

```
          E1                 E2 (charge ok at trial end)
 (none) ------> TRIALING ------------------------------> ACTIVE <------+
                  |  \                                     |  ^  \      | E8 settled (retry / member / webhook)
        E11 flag  |   \ E5 (first charge failed)           |  |   \ E4  |
        at trial  |    +--------------------------------+  |  |    renewal ok
        end       v                                     v  |  |
              CANCELED <---- E11 (flag set at period/grace end, CANCEL_NOW) ---- PAST_DUE  (E6 retries, E7 grace expiry: access READ_ONLY)
                 ^  \                                      ^
     E13 resub   |   +-- E13 --> ACTIVE                    +-- E5 from ACTIVE (renewal failed)
                 |
              EXPIRED <---- E12 (manual term over)          COMPLIMENTARY: E16 any -> ACTIVE(comp), E17 -> ACTIVE (card) or PAST_DUE/NO_CARD
```

Examples of combined states (every example in this document that carries a `state` also satisfies section 3.1; `check_examples.py` verifies it):

- **Scheduled cancellation but still ACTIVE / FULL**: `lifecycleStatus ACTIVE`, `cancelAtPeriodEnd true`, `paymentStatus CURRENT`, `accessState FULL` (example `summary-cancel-scheduled`, section 9.1).
- **PAST_DUE + RETRYING + GRACE**: the first renewal charge failed, retries are scheduled, access is still full with reminders (example `summary-grace`).
- **PAST_DUE + FAILED + READ_ONLY**: grace over, unpaid (example `summary-read-only`).
- **PAST_DUE + PENDING + GRACE**: a retry or "update card and retry" charge is in flight (example `card-verify-result-retry-started`).
- **ACTIVE + CURRENT + READ_ONLY** with `manualRestriction`: an admin restriction (example `action-result-restrict`).

### 3.3 Who writes what

| Writer | Writes |
|---|---|
| `SubscriptionStateService` | the ONLY writer of `tenant_subscriptions` and of the `tenants` projection columns `subscription_status`, `trial_ends_at`, `access_state`, `billing_managed` (an architecture test enforces it). Callers (jobs, webhook worker, member API, admin API, the PLAN-01 bridge) submit events; the service validates the transition, writes, audits and publishes |
| billing job (trial conversion, renewals, retries, grace expiry, reminders) | E2-E8, E11, E12, E17, E20 |
| webhook worker and reconciliation | E2, E4, E5, E6, E8 and payment row transitions, always after `verify` (section 12) |
| member API | E3, E9, E10, E13, E14, E19 |
| platform admin API | E9-E19 via `actions`, `complimentary`, refunds (the refund itself changes no state, section 7.10) |
| the PLAN-01 bridge `PUT /platform/tenants/{slug}/plan` | only for `MANUAL` tenants, routed through the service and audited as MANUAL; `409 BILLING_MANAGED` for a tenant with `billingMode = PAYSTACK` and a live subscription (O22) |

### 3.4 Mapping to the existing projection and to `readOnly` ([plans.md](plans.md) section 13)

| `lifecycleStatus` | `tenants.subscription_status` (unchanged enum, so PLAN-02 and the PLAN-04 console keep working) |
|---|---|
| `TRIALING` | `TRIALING` |
| `ACTIVE` | `ACTIVE` |
| `PAST_DUE` | `PAST_DUE` |
| `CANCELED`, `EXPIRED` | `CANCELED` |

`tenants.access_state` and `tenants.billing_managed` (`billingMode = PAYSTACK`) are new projection columns. `readOnly` of `GET /api/v1/admin/subscription` stays computed per request, never cached, and becomes:

```
readOnly = (accessState == READ_ONLY)
        OR (NOT billingManaged AND ( status == CANCELED OR (status == TRIALING AND trialEndsAt != null AND now >= trialEndsAt) ))
```

so a billing-managed tenant whose trial just ended is NOT read-only (the instant a trial ends the system charges, it does not restrict: M1); a `GRACE` tenant is not read-only; `MANUAL` tenants and tenants without a subscription row keep the pre-billing rule unchanged. (Monitoring: the trial-conversion lag metric alerts when a billing-managed trial is overdue for more than a few rounds.)
Every plans.md section 13 example still holds. Platform tenant summaries gain the keys of [platform-tenants.md](platform-tenants.md#billing-fields-bill-00).

The keys added to every platform tenant summary object (list rows, detail, the `tenant` of an action response, the answer of `PUT .../plan`; [platform-tenants.md](platform-tenants.md#billing-fields-bill-00)), shown as a fragment of the object (a tenant with a running subscription, and one in grace):

<!-- fragment: tenant-billing-fields TenantBillingFields -->
```json
{
  "billingStatus": "ACTIVE",
  "accessState": "FULL",
  "paymentStatus": "CURRENT",
  "cardOnFile": true,
  "billingMode": "PAYSTACK"
}
```

<!-- fragment: tenant-billing-fields-grace TenantBillingFields -->
```json
{
  "billingStatus": "PAST_DUE",
  "accessState": "GRACE",
  "paymentStatus": "RETRYING",
  "cardOnFile": true,
  "billingMode": "PAYSTACK"
}
```

## 4. Platform billing settings and Paystack credentials (FR-08, FR-09, FR-13, FR-14, FR-21, 4.6)

Master tables: `billing_settings` (ONE row, enforced by a constraint, `version` optimistic lock, `updated_at`, `updated_by`) and `billing_paystack_credentials` (one row per environment). All SUPER_ADMIN only
(class `@PreAuthorize("hasRole('SUPER_ADMIN')")` plus the matcher; anonymous and tenant tokens `401`, non-SUPER_ADMIN platform admin `403`). Nothing below is hard-coded in code: every number is data (4.6, D2).
Credentials are **separate from the per-shop gateway configuration** (`PaymentGatewayConfig*`, `TenantPaymentGatewayProvider`): no billing code path reads or writes a shop's gateway config (D5).

### 4.1 Settings

`GET /api/v1/platform/billing/settings` returns `{ settings, version, updatedAt, updatedBy, credentials[2] }`. `PUT` takes every setting plus the `version` read (full replace; `BillingSettingsRequest`) and returns the same shape.
The seed (migration) is the **Default** column; identity fields are `null` until the owner supplies them (D4). Validation failures are the `400` field map (`{"description": "Validation failed", "data": {"<field>": "<message>"}}`, field = JSON property, list items indexed `retryOffsetsDays[1]`).

| Field | Type | Default | Bounds and rules | Message on violation |
|---|---|---|---|---|
| `billingEnabled` | boolean | `false` | master switch. `true` only when the active environment's credentials are `VERIFIED`. `false`: the platform behaves as before BILL (no card step, no charges, no reminders, billing endpoints answer `409 BILLING_NOT_CONFIGURED` except `summary`) | `billing cannot be enabled until the active environment's credentials are verified` |
| `activeEnvironment` | `TEST` \| `LIVE` | `TEST` | only an environment whose credentials are `VERIFIED` | `the environment's credentials are not verified` |
| `cardRequiredAtSignup` | boolean | `true` | effective only when `billingEnabled` and the active credentials are `VERIFIED` (D1) | |
| `graceDays` | integer | `7` | 0..60; 0 = no grace, access is restricted at the first failure (and `retryOffsetsDays` must be empty) | `must be between 0 and 60` |
| `retryOffsetsDays` | integer[] | `[1,3,5]` | 0..6 entries, strictly ascending, each >= 1 and <= `graceDays`; days after the FIRST failure | `must be strictly ascending, at most 6 values, each between 1 and graceDays` |
| `retryTimeOfDayUtc` | `HH:mm` \| null | `null` | `null` = the time of day of the original attempt | `must be HH:mm (00:00 to 23:59)` |
| `trialReminderDaysBefore` | integer[] | `[3,1]` | 0..5 distinct values 1..60 (days before the trial ends). "Trial ends today" is separate and always on (switch it off with `emailEnabled`) | `must be at most 5 distinct values between 1 and 60` |
| `renewalReminderDaysBefore` | integer[] | `[3]` | 0..5 distinct values 1..60 | same |
| `priceChangeNoticeDays` | integer | `30` | 0..365; 0 = a price change applies at the next renewal without notice (O8) | `must be between 0 and 365` |
| `graceReminderOffsetsDays` | integer[] | `[1,3,5]` | 0..6 distinct values 1..60, days after the first failure (default: the retry days) | `must be at most 6 distinct values between 1 and 60` |
| `graceEndingReminderDaysBefore` | integer[] | `[1]` | 0..3 distinct values 1..60, days before grace ends (the board's "plus 1 day before grace ends"; split out so it survives a `graceDays` change) | `must be at most 3 distinct values between 1 and 60` |
| `emailEnabled` | object | every key `true` | exactly one boolean per `NotificationKind` (section 16); in-app banners are not affected | `must contain exactly the notification kinds` |
| `invoiceNumberFormat` | string | `SUB-{YYYY}-{NNNNNN}` | 8..40 chars of `A-Za-z0-9 / _ . - { }` (no spaces), must contain `{NNNNNN}`; tokens `{YYYY}` (year of issue, UTC) and `{NNNNNN}` (counter, zero-padded to 6, grows beyond 6 digits when needed); no other braces | `must contain {NNNNNN} and only letters, digits and - / _ . { }` |
| `receiptNumberFormat` | string | `REC-{YYYY}-{NNNNNN}` | same | same |
| `creditNoteNumberFormat` | string | `CN-{YYYY}-{NNNNNN}` | same; the three formats must differ in their literal part (so numbers never collide) | `formats must differ` |
| `businessName` | string \| null | `null` | plain text 1..120 (blank is stored as `null`) | `must be 1 to 120 characters of plain text` |
| `businessAddress` | string \| null | `null` | plain text 1..300 | same style |
| `businessTin` | string \| null | `null` | plain text 1..40 | same style |
| `businessEmail` | string \| null | `null` | plain text, e-mail syntax, <= 254 | `must be an e-mail address` |
| `businessPhone` | string \| null | `null` | plain text 1..40 | |
| `taxLabel` | string \| null | `null` | plain text 1..40 (for example a name of a tax; none is assumed) | |
| `taxRateBps` | integer \| null | `null` | 0..10000; `null` = no tax line on any document (D4, O9) | `must be between 0 and 10000` |
| `taxInclusive` | boolean | `true` | `true`: prices are final and the tax is extracted; `false`: tax is added to the price (section 6.6) | |
| `invoiceFooterText` | string \| null | `null` | plain text 1..500 | |
| `attachPdfToEmail` | boolean | `false` | attach the PDF to document e-mails (O18) | |
| `cardVerificationAmountPesewas` | integer | `100` | 100..100,000 (GHS 1 to 1,000); charged and refunded at once to authorize a card (O1) | `must be between 100 and 100000` |
| `allowedChannels` | string[] | `["card"]` | 1..6 distinct of `card`, `mobile_money`, `bank`, `ussd`, `qr`, `bank_transfer`: the channels offered on the hosted checkout | `must be 1 to 6 distinct channels` |
| `recurringChannels` | string[] | `["card"]` | non-empty subset of `allowedChannels`: the channels whose authorizations may be charged off-session (O2 final: card only, section 20.1) | `must be a non-empty subset of allowedChannels` |
| `reconcileIntervalMinutes` | integer | `15` | 5..1440 | `must be between 5 and 1440` |
| `reconcileLookbackHours` | integer | `48` | 1..720 | `must be between 1 and 720` |
| `reconcileFullScanDays` | integer | `30` | 1..365 (nightly full scan window) | `must be between 1 and 365` |
| `billingRunIntervalSeconds` | integer | `60` | 10..3600 | `must be between 10 and 3600` |
| `webhookIpAllowlistEnabled` | boolean | `false` | HMAC is the control (O29); when `true`, `webhookIpAllowlist` must be non-empty | `provide at least one address` |
| `webhookIpAllowlist` | string[] | `[]` | <= 20 IPv4/IPv6 addresses or CIDR blocks | `must be IP addresses or CIDR blocks` |
| `refundMaxDays` | integer | `90` | 1..365: a refund is allowed this many days after `paidAt` (O11) | `must be between 1 and 365` |

### 4.2 Cross-field rules (server only; each has a 400 example or a table row)

1. `retryOffsetsDays` strictly ascending and every value `<= graceDays` (`graceDays = 0` therefore allows only `[]`).
2. `activeEnvironment` must name an environment with status `VERIFIED`; `billingEnabled = true` requires the active environment `VERIFIED`. Turning `billingEnabled` off or switching the environment while subscriptions exist is allowed; subscriptions keep their own environment (a LIVE subscription is never charged with TEST keys and vice versa; switching to LIVE means every member authorizes a card again, O17).
3. `recurringChannels` is a subset of `allowedChannels`.
4. The three number formats are valid and distinct in their literal part. Changing a format affects only documents issued afterwards.
5. `taxRateBps` set with `taxLabel` `null`: the tax line is rendered with the neutral label `Tax`; a blank label is never rendered (the PDF renderer and the JSON agree).

<!-- example: settings-defaults response BillingSettingsEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "settings": {
      "billingEnabled": false,
      "activeEnvironment": "TEST",
      "cardRequiredAtSignup": true,
      "graceDays": 7,
      "retryOffsetsDays": [
        1,
        3,
        5
      ],
      "retryTimeOfDayUtc": null,
      "trialReminderDaysBefore": [
        3,
        1
      ],
      "renewalReminderDaysBefore": [
        3
      ],
      "priceChangeNoticeDays": 30,
      "graceReminderOffsetsDays": [
        1,
        3,
        5
      ],
      "graceEndingReminderDaysBefore": [
        1
      ],
      "emailEnabled": {
        "TRIAL_REMINDER": true,
        "TRIAL_ENDS_TODAY": true,
        "TRIAL_ENDED": true,
        "PAYMENT_SUCCEEDED": true,
        "PAYMENT_FAILED": true,
        "RETRY_SCHEDULED": true,
        "GRACE_STARTED": true,
        "GRACE_REMINDER": true,
        "GRACE_ENDING": true,
        "ACCESS_RESTRICTED": true,
        "ACCESS_RESTORED": true,
        "RENEWAL_REMINDER": true,
        "UPGRADE_APPLIED": true,
        "DOWNGRADE_SCHEDULED": true,
        "DOWNGRADE_APPLIED": true,
        "CANCELLATION_SCHEDULED": true,
        "CANCELLATION_REVERSED": true,
        "SUBSCRIPTION_ENDED": true,
        "INVOICE_AVAILABLE": true,
        "REFUND_ISSUED": true,
        "PRICE_CHANGE_NOTICE": true,
        "COMPLIMENTARY_STARTED": true,
        "COMPLIMENTARY_ENDING": true,
        "COMPLIMENTARY_ENDED": true,
        "CARD_EXPIRING": true,
        "CARD_UPDATED": true
      },
      "invoiceNumberFormat": "SUB-{YYYY}-{NNNNNN}",
      "receiptNumberFormat": "REC-{YYYY}-{NNNNNN}",
      "creditNoteNumberFormat": "CN-{YYYY}-{NNNNNN}",
      "businessName": null,
      "businessAddress": null,
      "businessTin": null,
      "businessEmail": null,
      "businessPhone": null,
      "taxLabel": null,
      "taxRateBps": null,
      "taxInclusive": true,
      "invoiceFooterText": null,
      "attachPdfToEmail": false,
      "cardVerificationAmountPesewas": 100,
      "allowedChannels": [
        "card"
      ],
      "recurringChannels": [
        "card"
      ],
      "reconcileIntervalMinutes": 15,
      "reconcileLookbackHours": 48,
      "reconcileFullScanDays": 30,
      "billingRunIntervalSeconds": 60,
      "webhookIpAllowlistEnabled": false,
      "webhookIpAllowlist": [],
      "refundMaxDays": 90
    },
    "version": 0,
    "updatedAt": null,
    "updatedBy": null,
    "credentials": [
      {
        "environment": "TEST",
        "configured": false,
        "status": "UNSET",
        "publicKeyLast4": null,
        "secretKeyLast4": null,
        "verifiedAt": null,
        "failureCode": null,
        "lastWebhookAt": null,
        "lastWebhookOkAt": null,
        "webhookUrl": "https://api.shopfi.test/api/v1/platform/billing/webhooks/paystack/test",
        "version": 0,
        "updatedAt": null,
        "updatedBy": null
      },
      {
        "environment": "LIVE",
        "configured": false,
        "status": "UNSET",
        "publicKeyLast4": null,
        "secretKeyLast4": null,
        "verifiedAt": null,
        "failureCode": null,
        "lastWebhookAt": null,
        "lastWebhookOkAt": null,
        "webhookUrl": "https://api.shopfi.test/api/v1/platform/billing/webhooks/paystack/live",
        "version": 0,
        "updatedAt": null,
        "updatedBy": null
      }
    ]
  }
}
```

<!-- example: settings-configured response BillingSettingsEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "settings": {
      "billingEnabled": true,
      "activeEnvironment": "TEST",
      "cardRequiredAtSignup": true,
      "graceDays": 10,
      "retryOffsetsDays": [
        1,
        3,
        5,
        8
      ],
      "retryTimeOfDayUtc": null,
      "trialReminderDaysBefore": [
        3,
        1
      ],
      "renewalReminderDaysBefore": [
        3
      ],
      "priceChangeNoticeDays": 30,
      "graceReminderOffsetsDays": [
        1,
        3,
        5
      ],
      "graceEndingReminderDaysBefore": [
        1
      ],
      "emailEnabled": {
        "TRIAL_REMINDER": true,
        "TRIAL_ENDS_TODAY": true,
        "TRIAL_ENDED": true,
        "PAYMENT_SUCCEEDED": true,
        "PAYMENT_FAILED": true,
        "RETRY_SCHEDULED": true,
        "GRACE_STARTED": true,
        "GRACE_REMINDER": true,
        "GRACE_ENDING": true,
        "ACCESS_RESTRICTED": true,
        "ACCESS_RESTORED": true,
        "RENEWAL_REMINDER": true,
        "UPGRADE_APPLIED": true,
        "DOWNGRADE_SCHEDULED": true,
        "DOWNGRADE_APPLIED": true,
        "CANCELLATION_SCHEDULED": true,
        "CANCELLATION_REVERSED": true,
        "SUBSCRIPTION_ENDED": true,
        "INVOICE_AVAILABLE": true,
        "REFUND_ISSUED": true,
        "PRICE_CHANGE_NOTICE": true,
        "COMPLIMENTARY_STARTED": true,
        "COMPLIMENTARY_ENDING": true,
        "COMPLIMENTARY_ENDED": true,
        "CARD_EXPIRING": true,
        "CARD_UPDATED": true
      },
      "invoiceNumberFormat": "SUB-{YYYY}-{NNNNNN}",
      "receiptNumberFormat": "REC-{YYYY}-{NNNNNN}",
      "creditNoteNumberFormat": "CN-{YYYY}-{NNNNNN}",
      "businessName": null,
      "businessAddress": null,
      "businessTin": null,
      "businessEmail": null,
      "businessPhone": null,
      "taxLabel": null,
      "taxRateBps": null,
      "taxInclusive": true,
      "invoiceFooterText": null,
      "attachPdfToEmail": false,
      "cardVerificationAmountPesewas": 100,
      "allowedChannels": [
        "card"
      ],
      "recurringChannels": [
        "card"
      ],
      "reconcileIntervalMinutes": 15,
      "reconcileLookbackHours": 48,
      "reconcileFullScanDays": 30,
      "billingRunIntervalSeconds": 60,
      "webhookIpAllowlistEnabled": false,
      "webhookIpAllowlist": [],
      "refundMaxDays": 90
    },
    "version": 4,
    "updatedAt": "2026-11-29T09:10:00Z",
    "updatedBy": "admin@shopfi.test",
    "credentials": [
      {
        "environment": "TEST",
        "configured": true,
        "status": "VERIFIED",
        "publicKeyLast4": "Qx7Z",
        "secretKeyLast4": "k3Pd",
        "verifiedAt": "2026-11-29T08:00:00Z",
        "failureCode": null,
        "lastWebhookAt": "2026-11-29T08:05:12Z",
        "lastWebhookOkAt": "2026-11-29T08:05:12Z",
        "webhookUrl": "https://api.shopfi.test/api/v1/platform/billing/webhooks/paystack/test",
        "version": 2,
        "updatedAt": "2026-11-29T07:58:30Z",
        "updatedBy": "admin@shopfi.test"
      },
      {
        "environment": "LIVE",
        "configured": false,
        "status": "UNSET",
        "publicKeyLast4": null,
        "secretKeyLast4": null,
        "verifiedAt": null,
        "failureCode": null,
        "lastWebhookAt": null,
        "lastWebhookOkAt": null,
        "webhookUrl": "https://api.shopfi.test/api/v1/platform/billing/webhooks/paystack/live",
        "version": 0,
        "updatedAt": null,
        "updatedBy": null
      }
    ]
  }
}
```

`PUT` (the request below saves grace 7 days, the identity name, a tax label and an illustrative 15% rate; real values come from the owner):

<!-- example: settings-update-request valid BillingSettingsRequest -->
```json
{
  "billingEnabled": true,
  "activeEnvironment": "TEST",
  "cardRequiredAtSignup": true,
  "graceDays": 7,
  "retryOffsetsDays": [
    1,
    3,
    5
  ],
  "retryTimeOfDayUtc": null,
  "trialReminderDaysBefore": [
    3,
    1
  ],
  "renewalReminderDaysBefore": [
    3
  ],
  "priceChangeNoticeDays": 30,
  "graceReminderOffsetsDays": [
    1,
    3,
    5
  ],
  "graceEndingReminderDaysBefore": [
    1
  ],
  "emailEnabled": {
    "TRIAL_REMINDER": true,
    "TRIAL_ENDS_TODAY": true,
    "TRIAL_ENDED": true,
    "PAYMENT_SUCCEEDED": true,
    "PAYMENT_FAILED": true,
    "RETRY_SCHEDULED": true,
    "GRACE_STARTED": true,
    "GRACE_REMINDER": true,
    "GRACE_ENDING": true,
    "ACCESS_RESTRICTED": true,
    "ACCESS_RESTORED": true,
    "RENEWAL_REMINDER": true,
    "UPGRADE_APPLIED": true,
    "DOWNGRADE_SCHEDULED": true,
    "DOWNGRADE_APPLIED": true,
    "CANCELLATION_SCHEDULED": true,
    "CANCELLATION_REVERSED": true,
    "SUBSCRIPTION_ENDED": true,
    "INVOICE_AVAILABLE": true,
    "REFUND_ISSUED": true,
    "PRICE_CHANGE_NOTICE": true,
    "COMPLIMENTARY_STARTED": true,
    "COMPLIMENTARY_ENDING": true,
    "COMPLIMENTARY_ENDED": true,
    "CARD_EXPIRING": true,
    "CARD_UPDATED": true
  },
  "invoiceNumberFormat": "SUB-{YYYY}-{NNNNNN}",
  "receiptNumberFormat": "REC-{YYYY}-{NNNNNN}",
  "creditNoteNumberFormat": "CN-{YYYY}-{NNNNNN}",
  "businessName": "Example Business Ltd",
  "businessAddress": null,
  "businessTin": null,
  "businessEmail": null,
  "businessPhone": null,
  "taxLabel": "VAT",
  "taxRateBps": 1500,
  "taxInclusive": true,
  "invoiceFooterText": null,
  "attachPdfToEmail": false,
  "cardVerificationAmountPesewas": 100,
  "allowedChannels": [
    "card"
  ],
  "recurringChannels": [
    "card"
  ],
  "reconcileIntervalMinutes": 15,
  "reconcileLookbackHours": 48,
  "reconcileFullScanDays": 30,
  "billingRunIntervalSeconds": 60,
  "webhookIpAllowlistEnabled": false,
  "webhookIpAllowlist": [],
  "refundMaxDays": 90,
  "version": 4
}
```

<!-- example: settings-update-response response BillingSettingsEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "settings": {
      "billingEnabled": true,
      "activeEnvironment": "TEST",
      "cardRequiredAtSignup": true,
      "graceDays": 7,
      "retryOffsetsDays": [
        1,
        3,
        5
      ],
      "retryTimeOfDayUtc": null,
      "trialReminderDaysBefore": [
        3,
        1
      ],
      "renewalReminderDaysBefore": [
        3
      ],
      "priceChangeNoticeDays": 30,
      "graceReminderOffsetsDays": [
        1,
        3,
        5
      ],
      "graceEndingReminderDaysBefore": [
        1
      ],
      "emailEnabled": {
        "TRIAL_REMINDER": true,
        "TRIAL_ENDS_TODAY": true,
        "TRIAL_ENDED": true,
        "PAYMENT_SUCCEEDED": true,
        "PAYMENT_FAILED": true,
        "RETRY_SCHEDULED": true,
        "GRACE_STARTED": true,
        "GRACE_REMINDER": true,
        "GRACE_ENDING": true,
        "ACCESS_RESTRICTED": true,
        "ACCESS_RESTORED": true,
        "RENEWAL_REMINDER": true,
        "UPGRADE_APPLIED": true,
        "DOWNGRADE_SCHEDULED": true,
        "DOWNGRADE_APPLIED": true,
        "CANCELLATION_SCHEDULED": true,
        "CANCELLATION_REVERSED": true,
        "SUBSCRIPTION_ENDED": true,
        "INVOICE_AVAILABLE": true,
        "REFUND_ISSUED": true,
        "PRICE_CHANGE_NOTICE": true,
        "COMPLIMENTARY_STARTED": true,
        "COMPLIMENTARY_ENDING": true,
        "COMPLIMENTARY_ENDED": true,
        "CARD_EXPIRING": true,
        "CARD_UPDATED": true
      },
      "invoiceNumberFormat": "SUB-{YYYY}-{NNNNNN}",
      "receiptNumberFormat": "REC-{YYYY}-{NNNNNN}",
      "creditNoteNumberFormat": "CN-{YYYY}-{NNNNNN}",
      "businessName": "Example Business Ltd",
      "businessAddress": null,
      "businessTin": null,
      "businessEmail": null,
      "businessPhone": null,
      "taxLabel": "VAT",
      "taxRateBps": 1500,
      "taxInclusive": true,
      "invoiceFooterText": null,
      "attachPdfToEmail": false,
      "cardVerificationAmountPesewas": 100,
      "allowedChannels": [
        "card"
      ],
      "recurringChannels": [
        "card"
      ],
      "reconcileIntervalMinutes": 15,
      "reconcileLookbackHours": 48,
      "reconcileFullScanDays": 30,
      "billingRunIntervalSeconds": 60,
      "webhookIpAllowlistEnabled": false,
      "webhookIpAllowlist": [],
      "refundMaxDays": 90
    },
    "version": 5,
    "updatedAt": "2026-11-29T09:20:00Z",
    "updatedBy": "admin@shopfi.test",
    "credentials": [
      {
        "environment": "TEST",
        "configured": true,
        "status": "VERIFIED",
        "publicKeyLast4": "Qx7Z",
        "secretKeyLast4": "k3Pd",
        "verifiedAt": "2026-11-29T08:00:00Z",
        "failureCode": null,
        "lastWebhookAt": "2026-11-29T08:05:12Z",
        "lastWebhookOkAt": "2026-11-29T08:05:12Z",
        "webhookUrl": "https://api.shopfi.test/api/v1/platform/billing/webhooks/paystack/test",
        "version": 2,
        "updatedAt": "2026-11-29T07:58:30Z",
        "updatedBy": "admin@shopfi.test"
      },
      {
        "environment": "LIVE",
        "configured": false,
        "status": "UNSET",
        "publicKeyLast4": null,
        "secretKeyLast4": null,
        "verifiedAt": null,
        "failureCode": null,
        "lastWebhookAt": null,
        "lastWebhookOkAt": null,
        "webhookUrl": "https://api.shopfi.test/api/v1/platform/billing/webhooks/paystack/live",
        "version": 0,
        "updatedAt": null,
        "updatedBy": null
      }
    ]
  }
}
```

Invalid requests (each is rejected by the schema and by the server with `400`, nothing written):

<!-- example: settings-invalid-grace-days invalid BillingSettingsRequest 400 -->
```json
{
  "billingEnabled": false,
  "activeEnvironment": "TEST",
  "cardRequiredAtSignup": true,
  "graceDays": 61,
  "retryOffsetsDays": [
    1,
    3,
    5
  ],
  "retryTimeOfDayUtc": null,
  "trialReminderDaysBefore": [
    3,
    1
  ],
  "renewalReminderDaysBefore": [
    3
  ],
  "priceChangeNoticeDays": 30,
  "graceReminderOffsetsDays": [
    1,
    3,
    5
  ],
  "graceEndingReminderDaysBefore": [
    1
  ],
  "emailEnabled": {
    "TRIAL_REMINDER": true,
    "TRIAL_ENDS_TODAY": true,
    "TRIAL_ENDED": true,
    "PAYMENT_SUCCEEDED": true,
    "PAYMENT_FAILED": true,
    "RETRY_SCHEDULED": true,
    "GRACE_STARTED": true,
    "GRACE_REMINDER": true,
    "GRACE_ENDING": true,
    "ACCESS_RESTRICTED": true,
    "ACCESS_RESTORED": true,
    "RENEWAL_REMINDER": true,
    "UPGRADE_APPLIED": true,
    "DOWNGRADE_SCHEDULED": true,
    "DOWNGRADE_APPLIED": true,
    "CANCELLATION_SCHEDULED": true,
    "CANCELLATION_REVERSED": true,
    "SUBSCRIPTION_ENDED": true,
    "INVOICE_AVAILABLE": true,
    "REFUND_ISSUED": true,
    "PRICE_CHANGE_NOTICE": true,
    "COMPLIMENTARY_STARTED": true,
    "COMPLIMENTARY_ENDING": true,
    "COMPLIMENTARY_ENDED": true,
    "CARD_EXPIRING": true,
    "CARD_UPDATED": true
  },
  "invoiceNumberFormat": "SUB-{YYYY}-{NNNNNN}",
  "receiptNumberFormat": "REC-{YYYY}-{NNNNNN}",
  "creditNoteNumberFormat": "CN-{YYYY}-{NNNNNN}",
  "businessName": null,
  "businessAddress": null,
  "businessTin": null,
  "businessEmail": null,
  "businessPhone": null,
  "taxLabel": null,
  "taxRateBps": null,
  "taxInclusive": true,
  "invoiceFooterText": null,
  "attachPdfToEmail": false,
  "cardVerificationAmountPesewas": 100,
  "allowedChannels": [
    "card"
  ],
  "recurringChannels": [
    "card"
  ],
  "reconcileIntervalMinutes": 15,
  "reconcileLookbackHours": 48,
  "reconcileFullScanDays": 30,
  "billingRunIntervalSeconds": 60,
  "webhookIpAllowlistEnabled": false,
  "webhookIpAllowlist": [],
  "refundMaxDays": 90,
  "version": 0
}
```

<!-- example: settings-invalid-duplicate-retry-days invalid BillingSettingsRequest 400 -->
```json
{
  "billingEnabled": false,
  "activeEnvironment": "TEST",
  "cardRequiredAtSignup": true,
  "graceDays": 7,
  "retryOffsetsDays": [
    1,
    3,
    3
  ],
  "retryTimeOfDayUtc": null,
  "trialReminderDaysBefore": [
    3,
    1
  ],
  "renewalReminderDaysBefore": [
    3
  ],
  "priceChangeNoticeDays": 30,
  "graceReminderOffsetsDays": [
    1,
    3,
    5
  ],
  "graceEndingReminderDaysBefore": [
    1
  ],
  "emailEnabled": {
    "TRIAL_REMINDER": true,
    "TRIAL_ENDS_TODAY": true,
    "TRIAL_ENDED": true,
    "PAYMENT_SUCCEEDED": true,
    "PAYMENT_FAILED": true,
    "RETRY_SCHEDULED": true,
    "GRACE_STARTED": true,
    "GRACE_REMINDER": true,
    "GRACE_ENDING": true,
    "ACCESS_RESTRICTED": true,
    "ACCESS_RESTORED": true,
    "RENEWAL_REMINDER": true,
    "UPGRADE_APPLIED": true,
    "DOWNGRADE_SCHEDULED": true,
    "DOWNGRADE_APPLIED": true,
    "CANCELLATION_SCHEDULED": true,
    "CANCELLATION_REVERSED": true,
    "SUBSCRIPTION_ENDED": true,
    "INVOICE_AVAILABLE": true,
    "REFUND_ISSUED": true,
    "PRICE_CHANGE_NOTICE": true,
    "COMPLIMENTARY_STARTED": true,
    "COMPLIMENTARY_ENDING": true,
    "COMPLIMENTARY_ENDED": true,
    "CARD_EXPIRING": true,
    "CARD_UPDATED": true
  },
  "invoiceNumberFormat": "SUB-{YYYY}-{NNNNNN}",
  "receiptNumberFormat": "REC-{YYYY}-{NNNNNN}",
  "creditNoteNumberFormat": "CN-{YYYY}-{NNNNNN}",
  "businessName": null,
  "businessAddress": null,
  "businessTin": null,
  "businessEmail": null,
  "businessPhone": null,
  "taxLabel": null,
  "taxRateBps": null,
  "taxInclusive": true,
  "invoiceFooterText": null,
  "attachPdfToEmail": false,
  "cardVerificationAmountPesewas": 100,
  "allowedChannels": [
    "card"
  ],
  "recurringChannels": [
    "card"
  ],
  "reconcileIntervalMinutes": 15,
  "reconcileLookbackHours": 48,
  "reconcileFullScanDays": 30,
  "billingRunIntervalSeconds": 60,
  "webhookIpAllowlistEnabled": false,
  "webhookIpAllowlist": [],
  "refundMaxDays": 90,
  "version": 0
}
```

<!-- example: settings-invalid-number-format invalid BillingSettingsRequest 400 -->
```json
{
  "billingEnabled": false,
  "activeEnvironment": "TEST",
  "cardRequiredAtSignup": true,
  "graceDays": 7,
  "retryOffsetsDays": [
    1,
    3,
    5
  ],
  "retryTimeOfDayUtc": null,
  "trialReminderDaysBefore": [
    3,
    1
  ],
  "renewalReminderDaysBefore": [
    3
  ],
  "priceChangeNoticeDays": 30,
  "graceReminderOffsetsDays": [
    1,
    3,
    5
  ],
  "graceEndingReminderDaysBefore": [
    1
  ],
  "emailEnabled": {
    "TRIAL_REMINDER": true,
    "TRIAL_ENDS_TODAY": true,
    "TRIAL_ENDED": true,
    "PAYMENT_SUCCEEDED": true,
    "PAYMENT_FAILED": true,
    "RETRY_SCHEDULED": true,
    "GRACE_STARTED": true,
    "GRACE_REMINDER": true,
    "GRACE_ENDING": true,
    "ACCESS_RESTRICTED": true,
    "ACCESS_RESTORED": true,
    "RENEWAL_REMINDER": true,
    "UPGRADE_APPLIED": true,
    "DOWNGRADE_SCHEDULED": true,
    "DOWNGRADE_APPLIED": true,
    "CANCELLATION_SCHEDULED": true,
    "CANCELLATION_REVERSED": true,
    "SUBSCRIPTION_ENDED": true,
    "INVOICE_AVAILABLE": true,
    "REFUND_ISSUED": true,
    "PRICE_CHANGE_NOTICE": true,
    "COMPLIMENTARY_STARTED": true,
    "COMPLIMENTARY_ENDING": true,
    "COMPLIMENTARY_ENDED": true,
    "CARD_EXPIRING": true,
    "CARD_UPDATED": true
  },
  "invoiceNumberFormat": "INV-{YYYY}",
  "receiptNumberFormat": "REC-{YYYY}-{NNNNNN}",
  "creditNoteNumberFormat": "CN-{YYYY}-{NNNNNN}",
  "businessName": null,
  "businessAddress": null,
  "businessTin": null,
  "businessEmail": null,
  "businessPhone": null,
  "taxLabel": null,
  "taxRateBps": null,
  "taxInclusive": true,
  "invoiceFooterText": null,
  "attachPdfToEmail": false,
  "cardVerificationAmountPesewas": 100,
  "allowedChannels": [
    "card"
  ],
  "recurringChannels": [
    "card"
  ],
  "reconcileIntervalMinutes": 15,
  "reconcileLookbackHours": 48,
  "reconcileFullScanDays": 30,
  "billingRunIntervalSeconds": 60,
  "webhookIpAllowlistEnabled": false,
  "webhookIpAllowlist": [],
  "refundMaxDays": 90,
  "version": 0
}
```

<!-- example: settings-invalid-tax-rate invalid BillingSettingsRequest 400 -->
```json
{
  "billingEnabled": false,
  "activeEnvironment": "TEST",
  "cardRequiredAtSignup": true,
  "graceDays": 7,
  "retryOffsetsDays": [
    1,
    3,
    5
  ],
  "retryTimeOfDayUtc": null,
  "trialReminderDaysBefore": [
    3,
    1
  ],
  "renewalReminderDaysBefore": [
    3
  ],
  "priceChangeNoticeDays": 30,
  "graceReminderOffsetsDays": [
    1,
    3,
    5
  ],
  "graceEndingReminderDaysBefore": [
    1
  ],
  "emailEnabled": {
    "TRIAL_REMINDER": true,
    "TRIAL_ENDS_TODAY": true,
    "TRIAL_ENDED": true,
    "PAYMENT_SUCCEEDED": true,
    "PAYMENT_FAILED": true,
    "RETRY_SCHEDULED": true,
    "GRACE_STARTED": true,
    "GRACE_REMINDER": true,
    "GRACE_ENDING": true,
    "ACCESS_RESTRICTED": true,
    "ACCESS_RESTORED": true,
    "RENEWAL_REMINDER": true,
    "UPGRADE_APPLIED": true,
    "DOWNGRADE_SCHEDULED": true,
    "DOWNGRADE_APPLIED": true,
    "CANCELLATION_SCHEDULED": true,
    "CANCELLATION_REVERSED": true,
    "SUBSCRIPTION_ENDED": true,
    "INVOICE_AVAILABLE": true,
    "REFUND_ISSUED": true,
    "PRICE_CHANGE_NOTICE": true,
    "COMPLIMENTARY_STARTED": true,
    "COMPLIMENTARY_ENDING": true,
    "COMPLIMENTARY_ENDED": true,
    "CARD_EXPIRING": true,
    "CARD_UPDATED": true
  },
  "invoiceNumberFormat": "SUB-{YYYY}-{NNNNNN}",
  "receiptNumberFormat": "REC-{YYYY}-{NNNNNN}",
  "creditNoteNumberFormat": "CN-{YYYY}-{NNNNNN}",
  "businessName": null,
  "businessAddress": null,
  "businessTin": null,
  "businessEmail": null,
  "businessPhone": null,
  "taxLabel": null,
  "taxRateBps": 10001,
  "taxInclusive": true,
  "invoiceFooterText": null,
  "attachPdfToEmail": false,
  "cardVerificationAmountPesewas": 100,
  "allowedChannels": [
    "card"
  ],
  "recurringChannels": [
    "card"
  ],
  "reconcileIntervalMinutes": 15,
  "reconcileLookbackHours": 48,
  "reconcileFullScanDays": 30,
  "billingRunIntervalSeconds": 60,
  "webhookIpAllowlistEnabled": false,
  "webhookIpAllowlist": [],
  "refundMaxDays": 90,
  "version": 0
}
```

<!-- example: settings-invalid-verification-amount invalid BillingSettingsRequest 400 -->
```json
{
  "billingEnabled": false,
  "activeEnvironment": "TEST",
  "cardRequiredAtSignup": true,
  "graceDays": 7,
  "retryOffsetsDays": [
    1,
    3,
    5
  ],
  "retryTimeOfDayUtc": null,
  "trialReminderDaysBefore": [
    3,
    1
  ],
  "renewalReminderDaysBefore": [
    3
  ],
  "priceChangeNoticeDays": 30,
  "graceReminderOffsetsDays": [
    1,
    3,
    5
  ],
  "graceEndingReminderDaysBefore": [
    1
  ],
  "emailEnabled": {
    "TRIAL_REMINDER": true,
    "TRIAL_ENDS_TODAY": true,
    "TRIAL_ENDED": true,
    "PAYMENT_SUCCEEDED": true,
    "PAYMENT_FAILED": true,
    "RETRY_SCHEDULED": true,
    "GRACE_STARTED": true,
    "GRACE_REMINDER": true,
    "GRACE_ENDING": true,
    "ACCESS_RESTRICTED": true,
    "ACCESS_RESTORED": true,
    "RENEWAL_REMINDER": true,
    "UPGRADE_APPLIED": true,
    "DOWNGRADE_SCHEDULED": true,
    "DOWNGRADE_APPLIED": true,
    "CANCELLATION_SCHEDULED": true,
    "CANCELLATION_REVERSED": true,
    "SUBSCRIPTION_ENDED": true,
    "INVOICE_AVAILABLE": true,
    "REFUND_ISSUED": true,
    "PRICE_CHANGE_NOTICE": true,
    "COMPLIMENTARY_STARTED": true,
    "COMPLIMENTARY_ENDING": true,
    "COMPLIMENTARY_ENDED": true,
    "CARD_EXPIRING": true,
    "CARD_UPDATED": true
  },
  "invoiceNumberFormat": "SUB-{YYYY}-{NNNNNN}",
  "receiptNumberFormat": "REC-{YYYY}-{NNNNNN}",
  "creditNoteNumberFormat": "CN-{YYYY}-{NNNNNN}",
  "businessName": null,
  "businessAddress": null,
  "businessTin": null,
  "businessEmail": null,
  "businessPhone": null,
  "taxLabel": null,
  "taxRateBps": null,
  "taxInclusive": true,
  "invoiceFooterText": null,
  "attachPdfToEmail": false,
  "cardVerificationAmountPesewas": 50,
  "allowedChannels": [
    "card"
  ],
  "recurringChannels": [
    "card"
  ],
  "reconcileIntervalMinutes": 15,
  "reconcileLookbackHours": 48,
  "reconcileFullScanDays": 30,
  "billingRunIntervalSeconds": 60,
  "webhookIpAllowlistEnabled": false,
  "webhookIpAllowlist": [],
  "refundMaxDays": 90,
  "version": 0
}
```

A cross-field failure (not expressible in JSON Schema; field map as above):

<!-- example: settings-validation-error response ValidationErrorEnvelope 400 -->
```json
{
  "description": "Validation failed",
  "data": {
    "retryOffsetsDays": "every retry day must be <= graceDays and the list must be strictly ascending",
    "activeEnvironment": "the environment's credentials are not verified"
  }
}
```

Stale `version`: `409`, nothing written, the client keeps its edits and offers Reload / Review ([plans.md](plans.md) section 9):

<!-- example: settings-version-conflict response VersionConflictEnvelope 409 -->
```json
{
  "description": "Billing settings were changed by someone else. Reload and review.",
  "data": {
    "code": "BILLING_SETTINGS_VERSION_CONFLICT",
    "expectedVersion": 4,
    "currentVersion": 5
  }
}
```

### 4.3 When a setting takes effect

- **Failure policy** (`graceDays`, `retryOffsetsDays`, `retryTimeOfDayUtc`) is read at the moment of the FIRST failure of a subscription and stored as a schedule snapshot on the subscription (`grace_started_at`, `grace_ends_at`, `retry_schedule`); a later edit changes only future failures (D2, BILL-04 AC1).
- **Reminders** (`*ReminderDaysBefore`, `graceReminderOffsetsDays`, `graceEndingReminderDaysBefore`): a reminder that was already sent is never sent again (dedupe key, section 16); a changed list affects only reminders not yet sent; removing a value cancels its unsent reminder.
- **`priceChangeNoticeDays`** applies to price changes made after the edit (the notice already created keeps its dates).
- **Number formats and identity** apply to documents issued after the save; an issued document is immutable (section 10).
- **Operations** values are read at the start of each scheduler round.
- Every save writes one audit row with field-level before / after in the same transaction (`GET .../settings/audit`, section 4.6); secrets never appear (S2).

### 4.4 Credentials (write-only, S2)

One row per environment: `public_key`, `secret_key` (AES-GCM with a **versioned key prefix and AAD = row and field**; a separate `billing.encryption.key` is recommended, O17), `secret_last4`, `status`, `verified_at`, `last_webhook_at`, `last_webhook_ok_at`, `version`.
**No API ever returns a key.** Responses carry `configured`, the last 4 characters of each key and metadata only; the audit shows `changed`; logs, error messages and OpenAPI examples never contain a key; a test greps every response, log line and audit JSON of the suite for the secret and fails if found. A key cannot be read back, only replaced.

| `status` | Meaning |
|---|---|
| `UNSET` | no keys stored |
| `UNVERIFIED` | keys stored, not yet tested (set by every `PUT`) |
| `VERIFIED` | the last `verify` call succeeded (`verifiedAt`) |
| `FAILED` | the last `verify` call was rejected by Paystack (`failureCode: KEY_REJECTED`); a network failure or an open circuit does NOT change the status (`503 PROVIDER_UNAVAILABLE`) |

`PUT /api/v1/platform/billing/credentials/{environment}` (`environment` = `test` | `live`): body `{publicKey, secretKey}`; the prefixes must match the path (`pk_test_` / `sk_test_` for `test`, `pk_live_` / `sk_live_` for `live`, else `400` with the field message), 16..128 letters and digits after the prefix. It stores the secret encrypted, sets `UNVERIFIED`, resets `verified_at` and returns `CredentialStatus`. The new pair is used immediately (key rotation after a leak must not wait); the operator is expected to press Verify right away.
**A Paystack `401` or `403` on any billing call (our key is wrong, revoked or rotated) is a platform fault, never a member fault**: the environment status becomes `FAILED`, the billing jobs skip that environment until a `verify` succeeds (no charge is marked failed, no grace starts, no subscription changes state), and a metric and alert fire.
`CredentialStatus.webhookUrl` is the URL to paste in that Paystack dashboard (test and live dashboards each have ONE webhook URL, so the environment is carried in the PATH, never guessed from the body, M6); it is built from server configuration (`billing.webhook-base-url`), never from input.

<!-- example: credentials-request-test valid CredentialsRequest -->
```json
{
  "publicKey": "pk_test_DOCSEXAMPLEONLYNOTAKEY0001",
  "secretKey": "sk_test_DOCSEXAMPLEONLYNOTAKEY0002"
}
```

<!-- example: credentials-request-bad-key invalid CredentialsRequest 400 -->
```json
{
  "publicKey": "not-a-key",
  "secretKey": "sk_test_DOCSEXAMPLEONLYNOTAKEY0002"
}
```

<!-- example: credentials-saved response CredentialStatusEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "environment": "TEST",
    "configured": true,
    "status": "UNVERIFIED",
    "publicKeyLast4": "0001",
    "secretKeyLast4": "0002",
    "verifiedAt": null,
    "failureCode": null,
    "lastWebhookAt": null,
    "lastWebhookOkAt": null,
    "webhookUrl": "https://api.shopfi.test/api/v1/platform/billing/webhooks/paystack/test",
    "version": 1,
    "updatedAt": "2026-11-29T07:58:30Z",
    "updatedBy": "admin@shopfi.test"
  }
}
```

<!-- example: credentials-prefix-mismatch response ValidationErrorEnvelope 400 -->
```json
{
  "description": "Validation failed",
  "data": {
    "secretKey": "must carry the key prefix of the test environment"
  }
}
```

`POST .../credentials/{environment}/verify` (throttled per admin, section 18) calls Paystack with that environment's key (a cheap authenticated call, for example a transaction list with `perPage=1`). Paystack accepts: `VERIFIED`, `verifiedAt` set; Paystack answers 401: `FAILED` with `failureCode KEY_REJECTED`. Both are `200` with the new `CredentialStatus`
(a failed verification is a result, not a server error). No keys stored: `409 CREDENTIALS_NOT_CONFIGURED`. Paystack unreachable, circuit open or timeout: `503 PROVIDER_UNAVAILABLE` with `Retry-After`, status unchanged.

<!-- example: credentials-verified response CredentialStatusEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "environment": "TEST",
    "configured": true,
    "status": "VERIFIED",
    "publicKeyLast4": "Qx7Z",
    "secretKeyLast4": "k3Pd",
    "verifiedAt": "2026-11-29T08:00:00Z",
    "failureCode": null,
    "lastWebhookAt": "2026-11-29T08:05:12Z",
    "lastWebhookOkAt": "2026-11-29T08:05:12Z",
    "webhookUrl": "https://api.shopfi.test/api/v1/platform/billing/webhooks/paystack/test",
    "version": 2,
    "updatedAt": "2026-11-29T07:58:30Z",
    "updatedBy": "admin@shopfi.test"
  }
}
```

<!-- example: credentials-verify-failed response CredentialStatusEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "environment": "LIVE",
    "configured": true,
    "status": "FAILED",
    "publicKeyLast4": "0001",
    "secretKeyLast4": "0002",
    "verifiedAt": null,
    "failureCode": "KEY_REJECTED",
    "lastWebhookAt": null,
    "lastWebhookOkAt": null,
    "webhookUrl": "https://api.shopfi.test/api/v1/platform/billing/webhooks/paystack/live",
    "version": 1,
    "updatedAt": "2026-11-29T09:30:00Z",
    "updatedBy": "admin@shopfi.test"
  }
}
```

<!-- example: credentials-not-configured response CodeErrorEnvelope 409 -->
```json
{
  "description": "No keys are stored for this environment.",
  "data": {
    "code": "CREDENTIALS_NOT_CONFIGURED"
  }
}
```

<!-- example: credentials-provider-unavailable response ProviderUnavailableEnvelope 503 -->
```json
{
  "description": "The payment provider is not reachable right now. Try again shortly.",
  "data": {
    "code": "PROVIDER_UNAVAILABLE"
  }
}
```

`GET /api/v1/platform/billing/credentials` returns both environments (`TEST` first):

<!-- example: credentials-list response CredentialListEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "credentials": [
      {
        "environment": "TEST",
        "configured": true,
        "status": "VERIFIED",
        "publicKeyLast4": "Qx7Z",
        "secretKeyLast4": "k3Pd",
        "verifiedAt": "2026-11-29T08:00:00Z",
        "failureCode": null,
        "lastWebhookAt": "2026-11-29T08:05:12Z",
        "lastWebhookOkAt": "2026-11-29T08:05:12Z",
        "webhookUrl": "https://api.shopfi.test/api/v1/platform/billing/webhooks/paystack/test",
        "version": 2,
        "updatedAt": "2026-11-29T07:58:30Z",
        "updatedBy": "admin@shopfi.test"
      },
      {
        "environment": "LIVE",
        "configured": false,
        "status": "UNSET",
        "publicKeyLast4": null,
        "secretKeyLast4": null,
        "verifiedAt": null,
        "failureCode": null,
        "lastWebhookAt": null,
        "lastWebhookOkAt": null,
        "webhookUrl": "https://api.shopfi.test/api/v1/platform/billing/webhooks/paystack/live",
        "version": 0,
        "updatedAt": null,
        "updatedBy": null
      }
    ]
  }
}
```

`last_webhook_at` is set when a request with ANY signature reaches the receiver of that environment and passes the signature check (a bad signature never touches it); `last_webhook_ok_at` when an event was stored. The console shows both so a mis-pasted URL or secret is visible at once (M6).

### 4.5 Test connection and the webhook URL

There is no separate webhook secret: Paystack signs with the SECRET KEY itself (`HMAC-SHA512` of the raw body), so "webhook configuration and verification" (FR-08) is exactly: per environment the keys, the URL above, the two timestamps and the `verify` call.

### 4.6 Settings audit

`GET /api/v1/platform/billing/settings/audit?page&size` (SUPER_ADMIN, newest first). One row per save, `changes[]` field level, credential fields as `changed` or last 4 only.

<!-- example: settings-audit-page response SettingsAuditPageEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "items": [
      {
        "id": 41,
        "occurredAt": "2026-11-29T09:20:00Z",
        "actorId": 1,
        "actorEmail": "admin@shopfi.test",
        "settingsVersion": 5,
        "changes": [
          {
            "field": "graceDays",
            "before": 10,
            "after": 7
          },
          {
            "field": "retryOffsetsDays",
            "before": [
              1,
              3,
              5,
              8
            ],
            "after": [
              1,
              3,
              5
            ]
          },
          {
            "field": "businessName",
            "before": null,
            "after": "Example Business Ltd"
          },
          {
            "field": "taxRateBps",
            "before": null,
            "after": 1500
          }
        ]
      },
      {
        "id": 40,
        "occurredAt": "2026-11-29T07:58:30Z",
        "actorId": 1,
        "actorEmail": "admin@shopfi.test",
        "settingsVersion": 4,
        "changes": [
          {
            "field": "credentials.TEST.secretKey",
            "before": "unset",
            "after": "changed"
          },
          {
            "field": "credentials.TEST.secretKeyLast4",
            "before": null,
            "after": "k3Pd"
          }
        ]
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 2,
    "totalPages": 1
  }
}
```

## 5. Signup: the card step (D1, FR-06, FR-09, M10)

**Card REQUIRED before the trial** (D1). The card is authorized by Paystack's hosted checkout after the e-mail is verified and before provisioning (O3 default): no store exists for an unverified or card-less signup, `trial_ends_at` is
still set ONCE when provisioning completes ([plans.md](plans.md) section 15, PLAN-02 AC9), and **no raw card data ever reaches Shopfi** (S1): the browser is redirected to `https://checkout.paystack.com/...` (or the fake checkout in tests) and returns to our callback with a `reference`.
Everything in this section is **additive** to [onboarding.md](onboarding.md): the existing status values (`PENDING | PROVISIONING | ACTIVE | FAILED`) never change (the wizard parses them), a client that ignores the new keys behaves as today, and with billing off the responses are byte-identical to before.

### 5.1 When the card step applies

The decision is taken at `POST /verify` (the moment the OTP is accepted) and stored on the signup row, so a settings change mid-signup never changes a signup in flight:

`card step = billingEnabled AND cardRequiredAtSignup AND active environment credentials VERIFIED`

- **Applies**: the verify response is `202` with `status: PROVISIONING`, `phase: AWAITING_CARD`, `cardRequired: true`, a single-use `cardSetupToken` and a `cardSetup` object. Provisioning is NOT queued yet.
- **Does not apply** (billing off, card not required, credentials not verified): signup behaves exactly as today. The new keys (`phase`, `cardRequired`, `cardSetupToken`, `cardSetupExpiresAt`, `cardSetup`) are **absent** (not `null`, not `false`); parsers treat absent as "no card step" (`cardRequired` absent is equivalent to `false`). The board text said `cardRequired = false`; omitting is chosen so that every existing onboarding example stays valid (section 20.3).

### 5.2 Additive fields (`OnboardingCardStatus` in `billing.schema.json`; added to `OnboardingStatus` in `onboarding.schema.json`)

| Key | Type | Where | Meaning |
|---|---|---|---|
| `phase` | `AWAITING_CARD` \| `PROVISIONING` \| `READY` | verify and status, only when the card step applies | the wizard's step. Mapping: `status PENDING` (code not entered yet): no `phase`. `status PROVISIONING` and card due: `AWAITING_CARD`. `status PROVISIONING` and card done: `PROVISIONING`. `status ACTIVE`: `READY`. `status FAILED`: `PROVISIONING` (retry with `/resend` then `/verify`; a stored card is kept, so the retry skips the card step) |
| `cardRequired` | boolean | same | `true` only in `AWAITING_CARD` (a card still has to be added); `false` afterwards |
| `cardSetupToken` | string | **verify response only**, only while `phase = AWAITING_CARD` | random (>= 32 characters of `A-Za-z0-9_-`), shown once, stored hashed, single use for a successful authorization, expires in <= 30 minutes, bound to the signup's tenant id. **Never returned by `/status`** (that endpoint is unauthenticated and polled by slug; returning the token there would hand it to anyone who knows a slug) |
| `cardSetupExpiresAt` | timestamp | with the token | expiry of the token |
| `cardSetup` | object | with the token | everything the card step displays, from the server only: `plan {code,name}` (the default sign-up plan), `trialDays`, `trialEndsAtEstimate` (now + `trialDays`; the real value is set when provisioning completes, a few seconds later, so the UI says "on or about"), `firstChargePesewas` (the plan's monthly price: the first charge, automatic at trial end), `verificationAmountPesewas` (the hold, refunded at once, O1), `currency` |

<!-- example: verify-accepted-card-required response OnboardingCardEnvelope 202 -->
```json
{
  "description": "Card required to start the trial",
  "data": {
    "slug": "acme-store",
    "status": "PROVISIONING",
    "phase": "AWAITING_CARD",
    "cardRequired": true,
    "cardSetupToken": "cst_Ab3dE5fG7hJ9kL2mN4pQ6rS8tU0vW1xY",
    "cardSetupExpiresAt": "2026-11-27T09:55:00Z",
    "cardSetup": {
      "plan": {
        "code": "STARTER",
        "name": "Starter"
      },
      "trialDays": 14,
      "trialEndsAtEstimate": "2026-12-11T09:30:00Z",
      "firstChargePesewas": 14900,
      "verificationAmountPesewas": 100,
      "currency": "GHS"
    }
  }
}
```

`GET /status?slug=` while the card is due and after it:

<!-- example: status-awaiting-card response OnboardingCardEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "slug": "acme-store",
    "status": "PROVISIONING",
    "phase": "AWAITING_CARD",
    "cardRequired": true
  }
}
```

<!-- example: status-provisioning-after-card response OnboardingCardEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "slug": "acme-store",
    "status": "PROVISIONING",
    "phase": "PROVISIONING",
    "cardRequired": false
  }
}
```

<!-- example: status-ready-with-card-step response OnboardingCardEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "slug": "acme-store",
    "status": "ACTIVE",
    "phase": "READY",
    "cardRequired": false,
    "storefrontUrl": "https://acme-store.shopfi.test",
    "adminLoginUrl": "https://acme-store.shopfi.test/admin/login"
  }
}
```

### 5.3 `POST /api/v1/platform/onboarding/card/initialize`

Public (`permitAll`, exact path, POST only, placed before the SUPER_ADMIN rule of `/api/v1/platform/**`), no cookie, no CORS beyond the onboarding zone ([onboarding.md](onboarding.md) section 11). Header `X-Card-Setup-Token: <cardSetupToken>` (required; never a query parameter, never logged). Body `{ "slug": "<signup slug>" }` (`CardSetupInitRequest`).
The server loads the signup by the token hash, checks that it belongs to `slug` and is in `AWAITING_CARD`, creates the Paystack transaction with: the active environment's keys, `channels = allowedChannels`, `amount = cardVerificationAmountPesewas`, `currency GHS`, the signup OWNER e-mail from our row (never from the request), a fresh reference `sub-card-<t|l>-<24 chars>`, metadata `{kind: card_setup, tenantId}`, and a callback URL built from configuration (never from input);
it records the setup (reference, amount, environment, token) and returns ONLY the hosted checkout URL and the reference (`200`). Initializing again for the same signup abandons the previous reference (it can no longer be verified).

<!-- example: card-initialize-request valid CardSetupInitRequest -->
```json
{
  "slug": "acme-store"
}
```

<!-- example: card-initialize-request-bad-slug invalid CardSetupInitRequest 400 -->
```json
{
  "slug": "A"
}
```

<!-- example: card-initialize-response response CardCheckoutInitEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "checkoutUrl": "https://checkout.paystack.com/0peioxfhpn",
    "reference": "sub-card-l-q8d2k5m1x7v3c9b4n6z0a2s8",
    "verificationAmountPesewas": 100,
    "currency": "GHS",
    "expiresAt": "2026-11-27T09:55:00Z"
  }
}
```

### 5.4 `GET /api/v1/platform/onboarding/card/callback-verify?reference=`

Header `X-Card-Setup-Token` required. The server calls Paystack `verify` for the reference (never trusts the redirect or any client value, S3) and accepts ONLY when all hold:
the reference is the one initialized for this token's signup, the transaction is `success`, amount equals `cardVerificationAmountPesewas`, currency `GHS`, `authorization.reusable == true`, `authorization.channel` is in `recurringChannels`, and the customer e-mail equals the signup owner e-mail (case-insensitive).
Then, in one unit of work: the authorization is stored (`authorization_code` encrypted, S1; display data brand, last4, expiry, bank), the verification payment is recorded `SUCCESS` with purpose `CARD_VERIFY` and **refunded immediately** (an adjustment row of type `REFUND`, reason `CARD_VERIFY`; **no invoice, no receipt, no credit note**: a verification hold is not a sale, BR-04), and provisioning is queued. A refund call that fails does not block the signup: it is retried by the billing job and shows as an open item in the console until done.
`200` returns the new phase and the card display data. The call is **idempotent**: repeating it with the same reference and token within 15 minutes of success returns the same `200` without side effects (a browser refresh on the callback page).
(`GET` with a server-side effect is the board's contract; the custom header makes it non-forgeable cross-site and the result is idempotent.)

<!-- example: card-callback-verified response CardSetupVerifyEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "slug": "acme-store",
    "status": "PROVISIONING",
    "phase": "PROVISIONING",
    "cardRequired": false,
    "card": {
      "brand": "visa",
      "last4": "4081",
      "expMonth": 12,
      "expYear": 2029,
      "bank": "Test Bank",
      "expiringSoon": false
    }
  }
}
```

Failures (the token survives failed attempts, not a success; at most 5 failed attempts per token, then `retryAllowed: false` and the token is dead):

| HTTP | `data.code` | When |
|---|---|---|
| 400 | `CARD_AUTH_FAILED`, `retryAllowed`, `reason` (`DECLINED`, `ABANDONED`, `NOT_REUSABLE`, `NOT_CARD`, `AMOUNT_MISMATCH`, `EMAIL_MISMATCH`, `UNKNOWN`) | declined, abandoned, non-reusable authorization, a non-recurring channel (for example mobile money while `recurringChannels` is `["card"]`), wrong amount (tampering), customer e-mail differs. The `description` is a fixed message (`We could not verify this card. Try another card.`); the `reason` is for the UI and logs, never raw provider text |
| 404 | none | token unknown, expired, already consumed by a success (and the replay window over), belonging to another signup, or the signup has no card step: ONE body for all of them, so nothing can be probed (`Card setup not found or expired`) |
| 409 | `BILLING_NOT_CONFIGURED` | billing was switched off or the credentials failed after the verify step |
| 429 | none | rate limit (section 18) |
| 503 | `PROVIDER_UNAVAILABLE` | Paystack unreachable; the wizard keeps the reference and offers "try again" (the verify is repeatable) |

<!-- example: card-auth-failed-declined response CardAuthFailedEnvelope 400 -->
```json
{
  "description": "We could not verify this card. Try another card.",
  "data": {
    "code": "CARD_AUTH_FAILED",
    "retryAllowed": true,
    "reason": "DECLINED"
  }
}
```

<!-- example: card-auth-failed-not-reusable response CardAuthFailedEnvelope 400 -->
```json
{
  "description": "We could not verify this card. Try another card.",
  "data": {
    "code": "CARD_AUTH_FAILED",
    "retryAllowed": true,
    "reason": "NOT_REUSABLE"
  }
}
```

<!-- example: card-setup-not-found response ErrorEnvelope 404 -->
```json
{
  "description": "Card setup not found or expired",
  "data": null
}
```

<!-- example: card-billing-not-configured response CodeErrorEnvelope 409 -->
```json
{
  "description": "Card payments are not available right now.",
  "data": {
    "code": "BILLING_NOT_CONFIGURED"
  }
}
```

### 5.5 After the card is on file

1. Provisioning runs as today (PENDING -> PROVISIONING -> ACTIVE). Provisioning failure leaves no subscription row and keeps the card (BILL-02 AC4).
2. In the SAME transaction as the tenant becoming `ACTIVE`, the subscription row is created: event E1 (`TRIALING`, payment `CURRENT`, access `FULL`, `billingMode PAYSTACK`, `environment` = the active one, `next_billing_at = trial_ends_at`, locked price = the default plan's monthly price at that moment, interval `MONTHLY`, anchor = `trial_ends_at`).
3. `trialDays` of the default plan (>= 1, [plans.md](plans.md) section 7) sets `trial_ends_at` once. A plan with `trialDays = 0` is impossible as the default sign-up plan.
4. The first charge happens automatically at `trial_ends_at` (section 7.2).
5. A signup abandoned at the card step is a PENDING tenant that follows the existing stale-signup purge ([onboarding.md](onboarding.md) section 6); the stored token and card setup rows die with it. No money was moved except a possible verification hold that was refunded or is listed for refund.
6. `POST /resend` and `POST /verify` on a signup that is `AWAITING_CARD`: allowed (a fresh OTP gives a fresh token and invalidates the old one; this is how a user who lost the token or whose token expired restarts). The OTP rules, lockouts and resend limits of onboarding.md are unchanged.

### 5.6 Frontend guidance (BILL-FE-02a)

Keep `cardSetupToken` in memory or `sessionStorage` for the wizard session and send it in the header; never in a URL, never logged. Allow-list the checkout URL host (`checkout.paystack.com`, plus the fake in the e2e config) before redirecting.
Validate `reference` (charset `A-Za-z0-9-`, length 8..64) on the callback route, call `callback-verify`, then `history.replaceState` the reference out of the URL and continue with status polling. Map `404` to "Start again", `CARD_AUTH_FAILED` to the fixed message with "Try another card" (re-initialize), and an unknown `phase` or `status` to the existing error screen. Card step copy (trial days, price, date, hold) comes only from `cardSetup`.

## 6. Money rules (BR-03, BR-07, BR-08, BR-09, FR-10, FR-16)

All amounts are integer GHS pesewas, `long` / `BigDecimal`, `RoundingMode.HALF_UP`, never `int`, never floating point (S9); currency is always `GHS` (`ck_plans_currency`, `ck_billing_payments_currency`). `round_half_up(x)` rounds to whole pesewas, a fraction of exactly `.5` away from zero (the values here are non-negative; a credit note is the negation of a non-negative computation).

### 6.1 Prices and the price lock

The plan prices are those of [plans.md](plans.md) section 3: `monthlyPricePesewas` and the derived `annualPricePesewas = round_half_up(monthly x 12 x (100 - annualDiscountPercent) / 100)` (one server function). A subscription does not read the live catalog to charge: it carries a **price lock**, a snapshot of the FINAL charge amount per interval of its plan:

```
lockedPricesPesewas = { MONTHLY: plan.monthlyPricePesewas, ANNUAL: plan.annualPricePesewas }   at the moment of the snapshot
```

The snapshot is taken at subscribe (E1 / E13), when a plan change is applied (E14) and at a renewal where a price change takes effect (6.4). Every charge amount comes from the lock (`lockedPricesPesewas[interval]`) or from a server computation of this section, never from a client value and never from the live catalog (BR-09, [plans.md](plans.md) section 15). The lock is part of the subscription detail and the ledger keeps the amount of every charge.

### 6.2 Billing periods

Periods are computed in UTC from the subscription's **anchor instant** (`anchorAt`: the trial end for a subscription that started with a trial, the payment instant of an `sb` charge, the end instant of a complimentary term). Period `n` ends at `anchor + n months` (MONTHLY) or `anchor + n years` (ANNUAL) using `java.time` UTC `plusMonths` / `plusYears`, **which clamp the day of month to the end of the target month** and never drift (the anchor, not the previous end, is the base, so an anchor on the 31st gives the 28th, then the 31st again). Period 0 is the trial. The first paid period is `[anchor, anchor + 1 interval)`.
A late payment does not move the anchor: the period continues from the original due date, the member does not gain days from paying late (O28). Reactivation after the subscription ended is a NEW subscription with a new anchor (section 7.5).

| anchor | interval | n | periodEnd(n) |
|---|---|---|---|
| `2026-12-11T09:30:00Z` | MONTHLY | 1 | `2027-01-11T09:30:00Z` |
| `2026-01-31T08:00:00Z` | MONTHLY | 1 | `2026-02-28T08:00:00Z` |
| `2026-01-31T08:00:00Z` | MONTHLY | 2 | `2026-03-31T08:00:00Z` |
| `2026-01-31T08:00:00Z` | MONTHLY | 3 | `2026-04-30T08:00:00Z` |
| `2026-01-31T08:00:00Z` | MONTHLY | 13 | `2027-02-28T08:00:00Z` |
| `2028-02-29T00:00:00Z` | ANNUAL | 1 | `2029-02-28T00:00:00Z` |
| `2028-02-29T00:00:00Z` | ANNUAL | 4 | `2032-02-29T00:00:00Z` |
| `2026-12-11T09:30:00Z` | ANNUAL | 1 | `2027-12-11T09:30:00Z` |

### 6.3 Classification and proration (FR-10, BR-07; O27)

**Classification** of a requested (plan, interval) against the subscription's current (plan, interval):

| Case | Classification | Timing |
|---|---|---|
| same plan and same interval | `SAME` | none (if a change is pending, choosing the current plan CLEARS it, `clearsPendingChange`) |
| same plan, other interval | `INTERVAL_CHANGE` | at the end of the current period, no charge now |
| other plan whose catalog `monthlyPricePesewas` is higher than the current plan's | `UPGRADE` | immediately, prorated charge now. Only with the CURRENT interval: asking for an upgrade and another interval at once is `409 CHANGE_NOT_ALLOWED` reason `UPGRADE_WITH_INTERVAL_CHANGE` (upgrade now, then schedule the interval change) |
| other plan whose catalog `monthlyPricePesewas` is lower or EQUAL | `DOWNGRADE` | at the end of the current period (with the requested interval), no charge now. Equal price counts as a downgrade: a deferred change never charges |
| any change while `TRIALING` | the same classification is reported, `inTrial: true` | immediately, **no charge**; the first charge at trial end uses the new plan, interval and price |

**Proration** is computed on the period actually being billed (monthly or annual) in whole seconds:

```
periodSeconds    = periodEnd - periodStart                     (UTC instants, in seconds; 28, 29, 30, 31, 365 or 366 days)
remainingSeconds = periodEnd - changeAt                        (0 <= remainingSeconds <= periodSeconds)
oldPrice         = lockedPricesPesewas[interval]               (the price the member is paying)
newPrice         = target plan's catalog price for the SAME interval (annual = the derived annual price)
delta            = round_half_up( (newPrice - oldPrice) x remainingSeconds / periodSeconds )     BigDecimal, then long
```

`chargeNowPesewas = delta` (plus tax in exclusive mode, 6.6) when `delta > 0`. If `delta` is `0` or negative (a change at the last second, or a target whose price is not above the locked price) the upgrade applies at once with `chargeNowPesewas = 0` (no payment row, no invoice). `changeAt` is the preview instant; **the amount shown by the preview is the amount charged** (the signed `previewToken` binds tenant, target plan and interval, `chargeNowPesewas`, `changeAt` and the subscription `version`, expires in 10 minutes; a different amount at submit is `409 PRICE_CHANGED`). Annual subscriptions use the real seconds of the period, leap years included.

Worked values (the BILL-03 parameterized test pins every row; `check_examples.py` recomputes them; a half-pesewa row shows `HALF_UP` against `HALF_EVEN`):

| id | old | new | periodStart | periodEnd | changeAt | periodSeconds | remainingSeconds | exact | **delta** |
|---|---|---|---|---|---|---|---|---|---|
| day1-31d | 39900 | 99900 | `2026-12-11T09:30:00Z` | `2027-01-11T09:30:00Z` | `2026-12-11T10:30:00Z` | 2678400 | 2674800 | 59919.3548 | **59919** |
| start-instant-31d | 39900 | 99900 | `2026-12-11T09:30:00Z` | `2027-01-11T09:30:00Z` | `2026-12-11T09:30:00Z` | 2678400 | 2678400 | 60000.0000 | **60000** |
| mid-31d | 39900 | 99900 | `2026-12-11T09:30:00Z` | `2027-01-11T09:30:00Z` | `2026-12-21T09:30:00Z` | 2678400 | 1814400 | 40645.1613 | **40645** |
| last-day-31d | 39900 | 99900 | `2026-12-11T09:30:00Z` | `2027-01-11T09:30:00Z` | `2027-01-10T09:30:00Z` | 2678400 | 86400 | 1935.4839 | **1935** |
| 31d-march | 39900 | 99900 | `2027-03-11T09:30:00Z` | `2027-04-11T09:30:00Z` | `2027-03-12T09:30:00Z` | 2678400 | 2592000 | 58064.5161 | **58065** |
| 28d-february-half | 14900 | 39900 | `2027-02-11T09:30:00Z` | `2027-03-11T09:30:00Z` | `2027-02-25T09:30:00Z` | 2419200 | 1209600 | 12500.0000 | **12500** |
| 28d-half-pesewa | 14899 | 39900 | `2027-02-11T09:30:00Z` | `2027-03-11T09:30:00Z` | `2027-02-25T09:30:00Z` | 2419200 | 1209600 | 12500.5000 | **12501** |
| 30d-april | 14900 | 39900 | `2027-04-11T09:30:00Z` | `2027-05-11T09:30:00Z` | `2027-04-21T09:30:00Z` | 2592000 | 1728000 | 16666.6667 | **16667** |
| anchor-31-clamped | 39900 | 99900 | `2027-01-31T08:00:00Z` | `2027-02-28T08:00:00Z` | `2027-02-14T08:00:00Z` | 2419200 | 1209600 | 30000.0000 | **30000** |
| free-to-paid | 0 | 14900 | `2026-12-11T09:30:00Z` | `2027-01-11T09:30:00Z` | `2026-12-21T09:30:00Z` | 2678400 | 1814400 | 10093.5484 | **10094** |
| annual-365d | 383040 | 959040 | `2026-12-11T09:30:00Z` | `2027-12-11T09:30:00Z` | `2027-06-11T09:30:00Z` | 31536000 | 15811200 | 288789.0411 | **288789** |
| annual-leap-366d | 383040 | 959040 | `2027-12-11T09:30:00Z` | `2028-12-11T09:30:00Z` | `2028-03-11T09:30:00Z` | 31622400 | 23760000 | 432786.8852 | **432787** |
| annual-last-day | 383040 | 959040 | `2027-12-11T09:30:00Z` | `2028-12-11T09:30:00Z` | `2028-12-10T09:30:00Z` | 31622400 | 86400 | 1573.7705 | **1574** |

Changes that charge nothing now: every `DOWNGRADE` and `INTERVAL_CHANGE` (annual to monthly included), every change inside the trial, `SAME`. A downgrade never refunds or credits an earlier prorated upgrade charge (O7: "no refund of the earlier prorated charge"; the unused part of the paid period is simply kept, BR-06).

### 6.4 Price changes of an existing plan (BR-09, D3; supersedes [plans.md](plans.md) section 15 / PLAN-03 AC6)

A change of a plan's `monthlyPricePesewas` or `annualDiscountPercent` never touches a running subscription silently. When the plan is saved, `PlansChangedEvent` (extended with the before and after of the price fields) creates a **price change notice** for every subscription on that plan whose locked price for its interval differs from the new final price:

```
notifiedAt           = the instant of the plan save
earliestEffectiveAt  = notifiedAt + priceChangeNoticeDays days            (0 allowed)
appliesAt            = the FIRST renewal instant R of the subscription with R >= earliestEffectiveAt
```

At `appliesAt` the lock is re-snapshotted from the catalog and the renewal charges the new price; renewals before it keep the old price (including renewals inside the notice window). The notice is e-mailed and shown as a banner when created (and a reminder is not repeated, section 16); the member can downgrade or cancel before it applies. A second save of the same plan creates a new notice that REPLACES the first (the notice clock restarts from the latest save); if the price returns to the locked value the notice is dropped. A subscriber who joined after the edit has the current price and no notice. `transactionFeeBps` is display-only and unaffected.
An upgrade to a plan uses that plan's CURRENT catalog price at once (a target plan has no lock); a downgrade takes effect at a renewal and locks the target's catalog price at that renewal.

| case | notifiedAt | noticeDays | renewals | earliestEffectiveAt | appliesAt |
|---|---|---|---|---|---|
| A | `2026-12-28T12:00:00Z` | 30 | 2027-01-11T09:30:00Z, 2027-02-11T09:30:00Z, 2027-03-11T09:30:00Z | `2027-01-27T12:00:00Z` | `2027-02-11T09:30:00Z` |
| B-no-notice | `2026-12-28T12:00:00Z` | 0 | 2027-01-11T09:30:00Z, 2027-02-11T09:30:00Z | `2026-12-28T12:00:00Z` | `2027-01-11T09:30:00Z` |
| C-exact-boundary | `2027-01-12T09:30:00Z` | 30 | 2027-01-11T09:30:00Z, 2027-02-11T09:30:00Z, 2027-03-11T09:30:00Z | `2027-02-11T09:30:00Z` | `2027-02-11T09:30:00Z` |
| D-edited-again-2027-01-20 | `2027-01-20T09:30:00Z` | 30 | 2027-01-11T09:30:00Z, 2027-02-11T09:30:00Z, 2027-03-11T09:30:00Z | `2027-02-19T09:30:00Z` | `2027-03-11T09:30:00Z` |
| E-notified-at-renewal-instant | `2027-01-11T09:30:00Z` | 0 | 2027-01-11T09:30:00Z, 2027-02-11T09:30:00Z | `2027-01-11T09:30:00Z` | `2027-01-11T09:30:00Z` |

### 6.5 Safety rules

- Every charge is `amount > 0` and `<= 100,000,000` pesewas (the plan price bound); a computed zero is "no charge", never a zero-amount Paystack call.
- A client-supplied amount is never used for a charge. The refund amount is the single client money input and is bounded by the server (6.7).
- The ledger stores amounts and currency per row; `amount`, `currency`, `reference`, `tenant` and `purpose` of a payment never change after insert; adjustments and documents are insert-only (S6, section 10).

### 6.6 Tax (D4, O9: no tax is assumed; the fields exist and are empty)

`taxRateBps` (integer basis points, 150 = 1.5%) and `taxInclusive` are settings. While `taxRateBps` is `null` there is no tax line on any document and every `taxPesewas` is `0`. When it is set (illustrative formulas, the rate and treatment come from the owner's tax advice, O9):

```
INCLUSIVE (default; the price is final, the tax is extracted):   charge = price
    net = round_half_up(charge x 10000 / (10000 + taxRateBps))          tax = charge - net
EXCLUSIVE (the tax is added to the price):                          net = price        tax = round_half_up(net x taxRateBps / 10000)        charge = net + tax
```

In exclusive mode the proration delta of 6.3 is the NET and the tax is added to it (`chargeTaxPesewas`); every amount a client is shown (`nextBilling.amountPesewas`, `chargeNowPesewas`) is the amount the card is charged. A rate of 0 behaves as no tax. A setting change applies to charges made after the save; issued documents keep their snapshot (section 10).

| mode | input | bps | net | tax | total |
|---|---|---|---|---|---|
| INCLUSIVE | 39900 | 1500 | 34696 | 5204 | 39900 |
| INCLUSIVE | 14900 | 1500 | 12957 | 1943 | 14900 |
| INCLUSIVE | 39900 | 250 | 38927 | 973 | 39900 |
| INCLUSIVE | 1 | 1500 | 1 | 0 | 1 |
| INCLUSIVE | 39900 | 0 | 39900 | 0 | 39900 |
| EXCLUSIVE | 39900 | 1500 | 39900 | 5985 | 45885 |
| EXCLUSIVE | 14901 | 1250 | 14901 | 1863 | 16764 |
| EXCLUSIVE | 1 | 5000 | 1 | 1 | 2 |
| EXCLUSIVE | 10 | 5000 | 10 | 5 | 15 |

### 6.7 Refunds and credit notes (BR-08, O11)

Refunds are **money only** and recorded by new rows, never by editing: a refund request row (mutable until terminal), an insert-only adjustment row of type `REFUND` (negative amount) written when the refund is completed, and a `CREDIT_NOTE` document. Access and the subscription are unchanged unless the same request says `cancelSubscription: true` (section 7.10).

```
refundableRemaining = payment.amount - sum(refunds COMPLETED) - sum(refunds PENDING)        (0 unless the payment is SUCCESS and purpose != CARD_VERIFY)
allowed when 1 <= amount <= refundableRemaining AND now <= paidAt + refundMaxDays days AND the payment's environment is the one being refunded
```

A credit note for a refund of `a` on an invoice with `net`, `tax`, `total` snapshots (all of the invoice, not of the payment): the NET is allocated proportionally and the LAST refund that brings the cumulative refunded amount to `total` takes the remainder, so the credit notes of an invoice always add up exactly to the invoice:

```
creditNet = round_half_up(a x net / total)        creditTax = a - creditNet              (credit note amounts are negative on the document)
last refund (cumulative = total):  creditNet = net - sum(previous creditNet)       creditTax = a - creditNet
```

| case | invoiceTotal | invoiceNet | invoiceTax | refund# | refundAmount | cumulative | creditNet | creditTax |
|---|---|---|---|---|---|---|---|---|
| A | 39900 | 34696 | 5204 | 1 | 10000 | 10000 | 8696 | 1304 |
| A | 39900 | 34696 | 5204 | 2 | 29900 | 39900 | 26000 | 3900 |
| B | 100 | 87 | 13 | 1 | 33 | 33 | 29 | 4 |
| B | 100 | 87 | 13 | 2 | 33 | 66 | 29 | 4 |
| B | 100 | 87 | 13 | 3 | 34 | 100 | 29 | 5 |

## 7. Lifecycle semantics (FR-06, FR-07, FR-09..12, FR-19, FR-20, BR-01..07)

Every flow below runs through `SubscriptionStateService` (section 3.3) and writes its audit row in the same transaction. "Charge" always means: payment row created first (deterministic reference), Paystack called, outcome resolved by `verify`, state changed only from the VERIFIED outcome (BR-03, S3, S4).

### 7.1 Trial with a card (D1, FR-09, BR-02)

A trial starts when provisioning completes (section 5.5): `TRIALING`, `trialEndsAt = now + plan.trialDays` (set once), full access with the plan's features and limits. The summary carries `trialEndsAt` and `serverTime` (the client derives "N days left" for display). A plan change inside the trial applies immediately with no charge (6.3). `trialDays` edits affect only trials that start later.

### 7.2 First charge at trial end

A dedicated scheduler (PLATFORM scope, every `billingRunIntervalSeconds`, bounded batch, `FOR UPDATE SKIP LOCKED`, leader lock) selects `TRIALING` subscriptions of mode `PAYSTACK` with `trialEndsAt <= now` (a trial ending exactly now is due) and `cancelAtPeriodEnd = false`. For each: payment row `sub-<env>-<tenantId>-tc-0-1` (`TRIAL_CONVERSION`, amount `lockedPricesPesewas[interval]` plus tax in exclusive mode) is inserted BEFORE the Paystack call, event E3, `charge_authorization` with that reference, then `verify`.
Success: E2: `ACTIVE`, `anchorAt = trialEndsAt` (not the charge time), period `[trialEndsAt, trialEndsAt + 1 interval)`, `next_billing_at` = period end, invoice and receipt, `PaymentSucceeded` event. Failure: E5 and the failure policy of section 8 (the same policy for the first charge and for renewals, D2). A timeout leaves the row `PENDING`; the verify / reconcile path resolves it; a second call with the same reference is never made as a new charge (a duplicate-reference answer from Paystack is treated as "exists, verify it").
A trial is never charged without a card on file: a `PAYSTACK` subscription always has one; a `MANUAL` one never enters this job (7.12).

### 7.3 Cancel during the trial (D5)

`cancelAtPeriodEnd = true` keeps full access until `trialEndsAt`, the job does NOT charge, and at `trialEndsAt` the subscription becomes `CANCELED` (E11, access `READ_ONLY`, payment `NONE`). Reactivate before `trialEndsAt` clears the flag; after it the subscription has ended (7.5).

<!-- example: trial-cancel-result response CancelResultEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "cancelEffectiveAt": "2026-12-11T09:30:00Z",
    "summary": {
      "view": "FULL",
      "serverTime": "2026-12-03T10:00:00Z",
      "billingEnabled": true,
      "billingMode": "PAYSTACK",
      "plan": {
        "id": 2,
        "code": "GROWTH",
        "name": "Growth",
        "monthlyPricePesewas": 39900,
        "annualPricePesewas": 383040
      },
      "interval": "MONTHLY",
      "state": {
        "lifecycleStatus": "TRIALING",
        "cancelAtPeriodEnd": true,
        "complimentary": false,
        "paymentStatus": "CURRENT",
        "accessState": "FULL"
      },
      "trialEndsAt": "2026-12-11T09:30:00Z",
      "currentPeriod": null,
      "cancelEffectiveAt": "2026-12-11T09:30:00Z",
      "complimentaryUntil": null,
      "nextBilling": null,
      "lockedPricePesewas": 39900,
      "grace": null,
      "pendingChange": null,
      "priceChange": null,
      "card": {
        "brand": "visa",
        "last4": "4081",
        "expMonth": 12,
        "expYear": 2029,
        "bank": "Test Bank",
        "expiringSoon": false
      },
      "actions": {
        "canChangePlan": true,
        "canCancel": false,
        "canReactivate": true,
        "canUpdateCard": true,
        "canRetryPayment": false,
        "canSubscribe": false
      },
      "banners": [
        {
          "kind": "CANCELLATION_SCHEDULED",
          "severity": "INFO",
          "dismissible": true,
          "params": {
            "effectiveAt": "2026-12-11T09:30:00Z"
          }
        }
      ],
      "version": 4
    }
  }
}
```

### 7.4 Cancel and reactivate (FR-19, FR-20, BR-05)

- **Cancel** (`POST /api/v1/admin/billing/cancel`, body `{reason?}` plain text <= 500; ADMIN): sets `cancelAtPeriodEnd`, refunds nothing, access stays `FULL` until the end of the paid period (`cancelEffectiveAt` = `currentPeriod.end`; the trial end while `TRIALING`; `grace.endsAt` while `PAST_DUE`, O25: access follows the grace schedule and the subscription ends at grace end; if the amount is settled in grace the paid period then runs to its end and the flag takes effect there). It clears a pending plan change. The response states the effective date. Cancel is idempotent: repeating it changes nothing and writes no second audit row. Both are audited with the reason and the ADMIN user id.
- **Reactivate** (`POST /api/v1/admin/billing/reactivate`): before the subscription ended, clears the flag and keeps the same authorization (FR-20; subject to the authorization still being valid: an expired card is shown by the card-expiring banner and the next charge fails into the failure policy). During grace it clears the flag but does NOT skip payment. After the end: `409 USE_SUBSCRIBE`.

<!-- example: cancel-request valid CancelRequest -->
```json
{
  "reason": "Closing the shop for now"
}
```

<!-- example: cancel-request-empty valid CancelRequest -->
```json
{}
```

<!-- example: cancel-request-bad-reason invalid CancelRequest 400 -->
```json
{
  "reason": "line break"
}
```

<!-- example: cancel-result response CancelResultEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "cancelEffectiveAt": "2027-01-11T09:30:00Z",
    "summary": {
      "view": "FULL",
      "serverTime": "2027-01-02T10:00:00Z",
      "billingEnabled": true,
      "billingMode": "PAYSTACK",
      "plan": {
        "id": 2,
        "code": "GROWTH",
        "name": "Growth",
        "monthlyPricePesewas": 39900,
        "annualPricePesewas": 383040
      },
      "interval": "MONTHLY",
      "state": {
        "lifecycleStatus": "ACTIVE",
        "cancelAtPeriodEnd": true,
        "complimentary": false,
        "paymentStatus": "CURRENT",
        "accessState": "FULL"
      },
      "trialEndsAt": null,
      "currentPeriod": {
        "start": "2026-12-11T09:30:00Z",
        "end": "2027-01-11T09:30:00Z"
      },
      "cancelEffectiveAt": "2027-01-11T09:30:00Z",
      "complimentaryUntil": null,
      "nextBilling": null,
      "lockedPricePesewas": 39900,
      "grace": null,
      "pendingChange": null,
      "priceChange": null,
      "card": {
        "brand": "visa",
        "last4": "4081",
        "expMonth": 12,
        "expYear": 2029,
        "bank": "Test Bank",
        "expiringSoon": false
      },
      "actions": {
        "canChangePlan": true,
        "canCancel": false,
        "canReactivate": true,
        "canUpdateCard": true,
        "canRetryPayment": false,
        "canSubscribe": false
      },
      "banners": [
        {
          "kind": "CANCELLATION_SCHEDULED",
          "severity": "INFO",
          "dismissible": true,
          "params": {
            "effectiveAt": "2027-01-11T09:30:00Z"
          }
        }
      ],
      "version": 6
    }
  }
}
```

<!-- example: reactivate-result response SummaryEnvelopeFull 200 -->
```json
{
  "description": null,
  "data": {
    "view": "FULL",
    "serverTime": "2027-01-03T10:00:00Z",
    "billingEnabled": true,
    "billingMode": "PAYSTACK",
    "plan": {
      "id": 2,
      "code": "GROWTH",
      "name": "Growth",
      "monthlyPricePesewas": 39900,
      "annualPricePesewas": 383040
    },
    "interval": "MONTHLY",
    "state": {
      "lifecycleStatus": "ACTIVE",
      "cancelAtPeriodEnd": false,
      "complimentary": false,
      "paymentStatus": "CURRENT",
      "accessState": "FULL"
    },
    "trialEndsAt": null,
    "currentPeriod": {
      "start": "2026-12-11T09:30:00Z",
      "end": "2027-01-11T09:30:00Z"
    },
    "cancelEffectiveAt": null,
    "complimentaryUntil": null,
    "nextBilling": {
      "at": "2027-01-11T09:30:00Z",
      "amountPesewas": 39900,
      "planCode": "GROWTH",
      "interval": "MONTHLY",
      "reason": "RENEWAL"
    },
    "lockedPricePesewas": 39900,
    "grace": null,
    "pendingChange": null,
    "priceChange": null,
    "card": {
      "brand": "visa",
      "last4": "4081",
      "expMonth": 12,
      "expYear": 2029,
      "bank": "Test Bank",
      "expiringSoon": false
    },
    "actions": {
      "canChangePlan": true,
      "canCancel": true,
      "canReactivate": false,
      "canUpdateCard": true,
      "canRetryPayment": false,
      "canSubscribe": false
    },
    "banners": [],
    "version": 7
  }
}
```

<!-- example: reactivate-too-late response CodeErrorEnvelope 409 -->
```json
{
  "description": "This subscription has ended. Subscribe again.",
  "data": {
    "code": "USE_SUBSCRIBE"
  }
}
```

### 7.5 A new subscription after the previous one ended (O26)

`POST /api/v1/admin/billing/subscribe` `{planId, interval}` + `Idempotency-Key` (ADMIN). Allowed when the subscription is `CANCELED` or `EXPIRED`, or when the tenant is in `MANUAL` mode with a card on file and no paid term (7.12). It needs a card on file (`409 CARD_REQUIRED`; the member adds one first, 9.5) and a plan that is active and public (else `409 CHANGE_NOT_ALLOWED` reason `PLAN_NOT_PURCHASABLE`). The member is **charged now** the plan price for the chosen interval (reference `sub-<env>-<tenantId>-sb-<generation>-1`, purpose `RENEWAL`, tax per 6.6), the anchor is the payment instant, the period is `[now, now + 1 interval)`, **no second trial**. Success E13 (`200`, `APPLIED`); unknown outcome `202 PAYMENT_PENDING`, the subscription stays ended until the verified success; a decline is `409 PAYMENT_FAILED` and starts no grace (BR-04). A live subscription: `409 ALREADY_SUBSCRIBED`.

<!-- example: subscribe-request valid SubscribeRequest -->
```json
{
  "planId": 2,
  "interval": "MONTHLY"
}
```

<!-- example: subscribe-request-bad-interval invalid SubscribeRequest 400 -->
```json
{
  "planId": 2,
  "interval": "WEEKLY"
}
```

<!-- example: subscribe-result response SubscribeResultEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "outcome": "APPLIED",
    "payment": {
      "id": 9010,
      "reference": "sub-l-12-sb-1-1",
      "purpose": "RENEWAL",
      "status": "SUCCESS",
      "amountPesewas": 39900,
      "currency": "GHS",
      "attemptNo": 1,
      "planCode": "GROWTH",
      "planName": "Growth",
      "interval": "MONTHLY",
      "periodStart": "2027-01-20T10:00:00Z",
      "periodEnd": "2027-02-20T10:00:00Z",
      "createdAt": "2027-01-20T10:00:00Z",
      "paidAt": "2027-01-20T10:00:06Z",
      "failureCode": null,
      "refundedPesewas": 0,
      "documents": []
    },
    "summary": {
      "view": "FULL",
      "serverTime": "2027-01-20T10:00:10Z",
      "billingEnabled": true,
      "billingMode": "PAYSTACK",
      "plan": {
        "id": 2,
        "code": "GROWTH",
        "name": "Growth",
        "monthlyPricePesewas": 39900,
        "annualPricePesewas": 383040
      },
      "interval": "MONTHLY",
      "state": {
        "lifecycleStatus": "ACTIVE",
        "cancelAtPeriodEnd": false,
        "complimentary": false,
        "paymentStatus": "CURRENT",
        "accessState": "FULL"
      },
      "trialEndsAt": null,
      "currentPeriod": {
        "start": "2027-01-20T10:00:00Z",
        "end": "2027-02-20T10:00:00Z"
      },
      "cancelEffectiveAt": null,
      "complimentaryUntil": null,
      "nextBilling": {
        "at": "2027-02-20T10:00:00Z",
        "amountPesewas": 39900,
        "planCode": "GROWTH",
        "interval": "MONTHLY",
        "reason": "RENEWAL"
      },
      "lockedPricePesewas": 39900,
      "grace": null,
      "pendingChange": null,
      "priceChange": null,
      "card": {
        "brand": "visa",
        "last4": "4081",
        "expMonth": 12,
        "expYear": 2029,
        "bank": "Test Bank",
        "expiringSoon": false
      },
      "actions": {
        "canChangePlan": true,
        "canCancel": true,
        "canReactivate": false,
        "canUpdateCard": true,
        "canRetryPayment": false,
        "canSubscribe": false
      },
      "banners": [],
      "version": 13
    }
  }
}
```

<!-- example: already-subscribed-error response CodeErrorEnvelope 409 -->
```json
{
  "description": "You already have a subscription.",
  "data": {
    "code": "ALREADY_SUBSCRIBED"
  }
}
```

<!-- example: use-subscribe-error response CodeErrorEnvelope 409 -->
```json
{
  "description": "This subscription has ended. Subscribe again.",
  "data": {
    "code": "USE_SUBSCRIBE"
  }
}
```

### 7.6 Upgrade now (FR-10, BR-07, O24)

Two calls: `POST /changes/preview` then `POST /changes` with the `previewToken` and an `Idempotency-Key` (section 9.6). For an `UPGRADE` the server inserts the payment row (reference `sub-<env>-<tenantId>-up-<changeSequence>-1`, purpose `UPGRADE`, the target plan and interval stored on the row), charges the stored authorization for `chargeNowPesewas`, verifies, and ONLY THEN applies the new plan, lock and entitlements (E14), writes invoice and receipt, and evicts the cache after commit so the next request has the new entitlements. The period, anchor and period end do not change; the next renewal charges the new plan's price.
A failed charge changes nothing (`409 PAYMENT_FAILED`: plan unchanged, no grace, no state change; the member is asked to update the card). An unknown outcome is `202 PAYMENT_PENDING`: plan unchanged until verified; the later verified success (webhook or reconciliation) applies the upgrade; a later FAILED applies nothing. While an upgrade charge is in flight every other change or retry is `409 PAYMENT_PENDING`. An upgrade is refused while `PAST_DUE` (`409 CHANGE_NOT_ALLOWED` reason `PAYMENT_OVERDUE`: pay the overdue amount first). Inside the trial an upgrade costs nothing (6.3).

### 7.7 Downgrade, interval change, several changes in one cycle (FR-11, FR-12, BR-06)

A `DOWNGRADE` or `INTERVAL_CHANGE` records ONE pending change effective at `currentPeriod.end` (during the trial: applied at once). Access and the paid period are untouched until then (BR-06); the summary shows "Switches to <plan> on <date>". At the renewal instant the change is applied first (E14, lock re-snapshotted from the catalog), then the renewal charge uses the new lock. If the usage exceeds the new plan's limits the data is kept and new creates are blocked ([plans.md](plans.md) section 15; the preview lists `overLimitFeatures`).
**Exactly one subscription row per tenant and at most one pending change** (partial unique index). Rules for sequences (BILL-03 AC6 tests every row on the fake):

| Sequence in one cycle | Result |
|---|---|
| upgrade, upgrade | two prorated charges; the second uses the lock written by the first as `oldPrice`; total charged = sum of the two documented prorations |
| upgrade, then downgrade | the downgrade is pending for the renewal; **no refund** of the earlier charge (O7) |
| downgrade, then upgrade | the upgrade applies now (classified against the CURRENT plan) and CLEARS the pending downgrade |
| downgrade, then another downgrade or interval change | the new one REPLACES the pending change |
| choosing the current plan and interval | clears the pending change (`SAME`, `clearsPendingChange`) |
| downgrade, then cancel | the cancellation clears the pending change |
| cancel, then upgrade | allowed; the flag stays; the member keeps the upgraded plan until the paid period ends |
| change, then card failure | plan unchanged, no grace |
| double submit (same `Idempotency-Key`) | one charge, the stored answer is replayed |
| two tabs (different keys, same preview) | the first applies; the second finds a newer subscription `version`: `409 SUBSCRIPTION_VERSION_CONFLICT` (a DB-level race on the pending change: `409 PENDING_CHANGE_CONFLICT`); never two charges |

### 7.8 Price change at the next renewal after notice (D3, BR-09)

See 6.4. **This supersedes PLAN-03 AC6** ("a price change affects only the next billing cycle, no billing yet"): the rule is now "applies from the first renewal on or after `notifiedAt + priceChangeNoticeDays`", with a notice to the member; [plans.md](plans.md) section 15 carries the superseding note in the same PR as this contract.

### 7.9 Complimentary plans (O13, FR-22)

`POST /api/v1/platform/billing/subscriptions/{slug}/complimentary` `{planId, until, reason, allowInactive?, expectedVersion}`: grants the plan with `complimentary = true`, lifecycle `ACTIVE`, access `FULL`, payment `NONE` (E16; any pending change and cancellation flag are cleared; a running trial or paid period is replaced). Billing is paused: no charges, no renewal or trial reminders (only the complimentary-ending notices, section 16), `next_billing_at` empty, no card required. `until`: a future instant at most 5 years ahead, or `null` for open-ended; an inactive plan needs `allowInactive: true`. Fully audited with the reason. `DELETE` (body `{reason, expectedVersion}`) ends it now.
**At the end** (E17): with a card on file the paid subscription resumes (a renewal charge is due at the end instant, anchor = the end instant, the same failure policy); without a card the failure policy `NO_CARD` applies (grace, reminders, no charge attempts). Complimentary subscriptions are excluded from revenue, MRR and active counts (section 14). `409 NOT_COMPLIMENTARY` for the end call on a paid subscription.

### 7.10 Refunds (FR-22, BR-08, O11)

`POST /api/v1/platform/billing/payments/{id}/refund` `{amountPesewas, reason, cancelSubscription?}` + `Idempotency-Key` (SUPER_ADMIN, throttled). Only a `SUCCESS` payment of a purpose other than `CARD_VERIFY`, within `refundMaxDays` of `paidAt`, in the same environment (`REFUND_NOT_ALLOWED` reasons `PAYMENT_NOT_SUCCESSFUL`, `CARD_VERIFY`, `WINDOW_EXPIRED`, `WRONG_ENVIRONMENT`, `NOTHING_REFUNDABLE`); `amountPesewas` 1..`refundableRemainingPesewas` (`409 REFUND_EXCEEDS_REMAINING` with `remainingPesewas`). Partial and repeated refunds are allowed up to the remaining amount.
The refund goes to Paystack with a deterministic key; the refund request row is `PENDING` until Paystack confirms: `200` `COMPLETED` (the adjustment row and the credit note exist) or `202` `PENDING` (finished by the `refund.processed` webhook or reconciliation; `refund.failed` makes it `FAILED` and frees the amount). The original payment, invoice and receipt are never edited (BR-08). The refund changes NO access and NO state, unless the same request carries `cancelSubscription: true`, which schedules non-renewal (E9, the same as `CANCEL_AT_PERIOD_END`; an immediate end is the separate `CANCEL_NOW` action). Refunding an upgrade charge does not revert the plan. A double click is one refund. A refund in TEST never touches a LIVE record.

<!-- example: refund-request valid RefundRequest -->
```json
{
  "amountPesewas": 10000,
  "reason": "Customer overcharged during onboarding"
}
```

<!-- example: refund-request-cancel valid RefundRequest -->
```json
{
  "amountPesewas": 5000,
  "reason": "Goodwill refund and closing the account",
  "cancelSubscription": true
}
```

<!-- example: refund-request-zero invalid RefundRequest 400 -->
```json
{
  "amountPesewas": 0,
  "reason": "Customer overcharged during onboarding"
}
```

<!-- example: refund-request-no-reason invalid RefundRequest 400 -->
```json
{
  "amountPesewas": 10000
}
```

<!-- example: refund-result-completed response RefundResultEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "refund": {
      "id": 3001,
      "paymentId": 9001,
      "amountPesewas": 10000,
      "status": "COMPLETED",
      "reason": "Customer overcharged during onboarding",
      "requestedBy": "admin@shopfi.test",
      "requestedAt": "2026-12-30T10:00:00Z",
      "completedAt": "2026-12-30T10:00:05Z",
      "adjustmentId": 7001,
      "creditNoteId": 507,
      "cancelledSubscription": false
    },
    "payment": {
      "id": 9001,
      "reference": "sub-l-12-tc-0-1",
      "purpose": "TRIAL_CONVERSION",
      "status": "SUCCESS",
      "amountPesewas": 39900,
      "currency": "GHS",
      "attemptNo": 1,
      "environment": "LIVE",
      "planCode": "GROWTH",
      "interval": "MONTHLY",
      "periodStart": "2026-12-11T09:30:00Z",
      "periodEnd": "2027-01-11T09:30:00Z",
      "createdAt": "2026-12-11T09:30:00Z",
      "paidAt": "2026-12-11T09:30:07Z",
      "verifiedAt": "2026-12-11T09:30:07Z",
      "feesPesewas": null,
      "failureCode": null,
      "flagged": false,
      "flagReason": null,
      "refundedPesewas": 10000,
      "pendingRefundPesewas": 0,
      "refundableRemainingPesewas": 29900,
      "refundableUntil": "2027-03-11T09:30:07Z",
      "documents": [
        {
          "id": 501,
          "type": "INVOICE",
          "number": "SUB-2026-000001"
        },
        {
          "id": 502,
          "type": "RECEIPT",
          "number": "REC-2026-000001"
        }
      ]
    }
  }
}
```

<!-- example: refund-result-pending response RefundResultEnvelope 202 -->
```json
{
  "description": null,
  "data": {
    "refund": {
      "id": 3002,
      "paymentId": 9004,
      "amountPesewas": 5000,
      "status": "PENDING",
      "reason": "Goodwill refund after the failed renewal",
      "requestedBy": "admin@shopfi.test",
      "requestedAt": "2027-01-13T08:00:00Z",
      "completedAt": null,
      "adjustmentId": null,
      "creditNoteId": null,
      "cancelledSubscription": false
    },
    "payment": {
      "id": 9004,
      "reference": "sub-l-12-rn-1-2",
      "purpose": "RETRY",
      "status": "SUCCESS",
      "amountPesewas": 39900,
      "currency": "GHS",
      "attemptNo": 2,
      "environment": "LIVE",
      "planCode": "GROWTH",
      "interval": "MONTHLY",
      "periodStart": "2027-01-11T09:30:00Z",
      "periodEnd": "2027-02-11T09:30:00Z",
      "createdAt": "2027-01-12T09:30:00Z",
      "paidAt": "2027-01-12T09:30:06Z",
      "verifiedAt": "2027-01-12T09:30:06Z",
      "feesPesewas": null,
      "failureCode": null,
      "flagged": false,
      "flagReason": null,
      "refundedPesewas": 0,
      "pendingRefundPesewas": 5000,
      "refundableRemainingPesewas": 34900,
      "refundableUntil": "2027-04-12T09:30:06Z",
      "documents": [
        {
          "id": 505,
          "type": "INVOICE",
          "number": "SUB-2027-000001"
        },
        {
          "id": 506,
          "type": "RECEIPT",
          "number": "REC-2027-000001"
        }
      ]
    }
  }
}
```

<!-- example: refund-exceeds-remaining response RefundExceedsEnvelope 409 -->
```json
{
  "description": "The refund is more than what is left to refund on this payment.",
  "data": {
    "code": "REFUND_EXCEEDS_REMAINING",
    "remainingPesewas": 29900
  }
}
```

<!-- example: refund-not-allowed-window response RefundNotAllowedEnvelope 409 -->
```json
{
  "description": "This payment is outside the refund window.",
  "data": {
    "code": "REFUND_NOT_ALLOWED",
    "reason": "WINDOW_EXPIRED"
  }
}
```

<!-- example: refund-not-allowed-verify response RefundNotAllowedEnvelope 409 -->
```json
{
  "description": "A card verification hold cannot be refunded here.",
  "data": {
    "code": "REFUND_NOT_ALLOWED",
    "reason": "CARD_VERIFY"
  }
}
```

### 7.11 Manual admin actions (FR-22, BR-10)

`POST /api/v1/platform/billing/subscriptions/{slug}/actions` `{action, reason, expectedVersion, ...}`; `reason` required (plain text 5..500), `expectedVersion` required (stale: `409 SUBSCRIPTION_VERSION_CONFLICT`), SUPER_ADMIN only, throttled per admin. Each action runs through `SubscriptionStateService`, is audited (actor admin id and e-mail, reason, before / after), evicts the cache after commit, and answers with the new `SubscriptionDetail`. A state in which the action is illegal: `409 ACTION_NOT_ALLOWED` with the `action`, `lifecycleStatus` and `accessState`.

| `action` | Extra fields | Legal from | Effect |
|---|---|---|---|
| `ACTIVATE` | `until?` (future, <= 5 years) | any live or ended state | `ACTIVE`, access `FULL`, payment `NONE`; without `until` the tenant becomes `MANUAL` and open-ended; with `until` a fixed term that ends in `EXPIRED` (E12). Not for a `complimentary` subscription (use the complimentary endpoints) |
| `RESTRICT` | | `TRIALING`, `ACTIVE`, `PAST_DUE` | access `READ_ONLY` now, `manualRestriction = true` (survives payments until `RESTORE`) |
| `RESTORE` | | with `manualRestriction` | clears it and recomputes the access from the lifecycle and grace rules |
| `EXTEND_TRIAL` | `trialEndsAt` (later than the current, in the future, <= now + 365 days) | `TRIALING` | E18: moves the first charge and the reminders (unsent trial reminders are rescheduled) |
| `CHANGE_PLAN` | `planId`, `interval?`, `charge?`, `allowInactive?` | `TRIALING`, `ACTIVE`, `PAST_DUE` | immediate, no charge; with `charge: true` through the normal prorated path (the stored card is charged) |
| `CANCEL_NOW` | | `TRIALING`, `ACTIVE`, `PAST_DUE` | E11 now: `CANCELED`, `READ_ONLY` (no refund) |
| `CANCEL_AT_PERIOD_END` | | same | E9 |
| `RETRY_PAYMENT_NOW` | | `PAST_DUE` | ONE attempt through the section 8 path (`PAYMENT_PENDING` while one is in flight) |
| `CLEAR_PENDING_CHANGE` | | with a pending change | clears it |

<!-- example: action-activate-request valid SubscriptionActionRequest -->
```json
{
  "action": "ACTIVATE",
  "reason": "Paid by bank transfer, manual activation",
  "expectedVersion": 9,
  "until": "2027-03-11T09:30:00Z"
}
```

<!-- example: action-restrict-request valid SubscriptionActionRequest -->
```json
{
  "action": "RESTRICT",
  "reason": "Chargeback suspected, restricting paid features",
  "expectedVersion": 9
}
```

<!-- example: action-restore-request valid SubscriptionActionRequest -->
```json
{
  "action": "RESTORE",
  "reason": "Verified payment received manually",
  "expectedVersion": 10
}
```

<!-- example: action-extend-trial-request valid SubscriptionActionRequest -->
```json
{
  "action": "EXTEND_TRIAL",
  "reason": "Onboarding delayed by the support team",
  "expectedVersion": 2,
  "trialEndsAt": "2027-01-27T10:00:00Z"
}
```

<!-- example: action-change-plan-request valid SubscriptionActionRequest -->
```json
{
  "action": "CHANGE_PLAN",
  "reason": "Customer agreed an upgrade by phone",
  "expectedVersion": 9,
  "planId": 3,
  "interval": "MONTHLY",
  "charge": true
}
```

<!-- example: action-cancel-now-request valid SubscriptionActionRequest -->
```json
{
  "action": "CANCEL_NOW",
  "reason": "Account closed at the owner's request",
  "expectedVersion": 9
}
```

<!-- example: action-cancel-at-period-end-request valid SubscriptionActionRequest -->
```json
{
  "action": "CANCEL_AT_PERIOD_END",
  "reason": "Owner asked to stop renewals",
  "expectedVersion": 9
}
```

<!-- example: action-retry-payment-request valid SubscriptionActionRequest -->
```json
{
  "action": "RETRY_PAYMENT_NOW",
  "reason": "Owner updated the card by phone",
  "expectedVersion": 9
}
```

<!-- example: action-clear-pending-change-request valid SubscriptionActionRequest -->
```json
{
  "action": "CLEAR_PENDING_CHANGE",
  "reason": "Owner changed their mind",
  "expectedVersion": 7
}
```

<!-- example: action-missing-reason invalid SubscriptionActionRequest 400 -->
```json
{
  "action": "RESTRICT",
  "expectedVersion": 9
}
```

<!-- example: action-short-reason invalid SubscriptionActionRequest 400 -->
```json
{
  "action": "RESTRICT",
  "reason": "no",
  "expectedVersion": 9
}
```

<!-- example: action-unknown invalid SubscriptionActionRequest 400 -->
```json
{
  "action": "DELETE_EVERYTHING",
  "reason": "this should never work",
  "expectedVersion": 9
}
```

<!-- example: action-result-restrict response SubscriptionDetailEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "tenant": {
      "id": 12,
      "slug": "acme",
      "displayName": "Acme Perfumes",
      "ownerEmail": "owner@acme.example"
    },
    "plan": {
      "id": 2,
      "code": "GROWTH",
      "name": "Growth",
      "monthlyPricePesewas": 39900,
      "annualPricePesewas": 383040
    },
    "interval": "MONTHLY",
    "state": {
      "lifecycleStatus": "ACTIVE",
      "cancelAtPeriodEnd": false,
      "complimentary": false,
      "paymentStatus": "CURRENT",
      "accessState": "READ_ONLY"
    },
    "billingMode": "PAYSTACK",
    "environment": "LIVE",
    "manualRestriction": true,
    "trialEndsAt": "2026-12-11T09:30:00Z",
    "currentPeriod": {
      "start": "2027-01-11T09:30:00Z",
      "end": "2027-02-11T09:30:00Z"
    },
    "anchorAt": "2026-12-11T09:30:00Z",
    "nextBillingAt": "2027-02-11T09:30:00Z",
    "lockedPricesPesewas": {
      "MONTHLY": 39900,
      "ANNUAL": 383040
    },
    "grace": null,
    "pendingChange": null,
    "priceChange": null,
    "complimentary": null,
    "card": {
      "brand": "visa",
      "last4": "4081",
      "expMonth": 12,
      "expYear": 2029,
      "bank": "Test Bank",
      "expiringSoon": false
    },
    "version": 10,
    "timeline": [
      {
        "at": "2027-01-11T09:30:04Z",
        "event": "CHARGE_FAILED",
        "from": {
          "lifecycleStatus": "ACTIVE",
          "cancelAtPeriodEnd": false,
          "complimentary": false,
          "paymentStatus": "CURRENT",
          "accessState": "FULL"
        },
        "to": {
          "lifecycleStatus": "PAST_DUE",
          "cancelAtPeriodEnd": false,
          "complimentary": false,
          "paymentStatus": "RETRYING",
          "accessState": "GRACE"
        },
        "actorType": "SYSTEM",
        "reference": "sub-l-12-rn-1-1"
      },
      {
        "at": "2026-12-21T09:30:05Z",
        "event": "UPGRADE_APPLIED",
        "from": {
          "lifecycleStatus": "ACTIVE",
          "cancelAtPeriodEnd": false,
          "complimentary": false,
          "paymentStatus": "CURRENT",
          "accessState": "FULL"
        },
        "to": {
          "lifecycleStatus": "ACTIVE",
          "cancelAtPeriodEnd": false,
          "complimentary": false,
          "paymentStatus": "CURRENT",
          "accessState": "FULL"
        },
        "actorType": "MEMBER",
        "reference": "sub-l-12-up-1-1"
      },
      {
        "at": "2026-12-11T09:30:07Z",
        "event": "TRIAL_CHARGE_SUCCEEDED",
        "from": {
          "lifecycleStatus": "TRIALING",
          "cancelAtPeriodEnd": false,
          "complimentary": false,
          "paymentStatus": "CURRENT",
          "accessState": "FULL"
        },
        "to": {
          "lifecycleStatus": "ACTIVE",
          "cancelAtPeriodEnd": false,
          "complimentary": false,
          "paymentStatus": "CURRENT",
          "accessState": "FULL"
        },
        "actorType": "SYSTEM",
        "reference": "sub-l-12-tc-0-1"
      },
      {
        "at": "2026-11-27T09:30:00Z",
        "event": "TRIAL_STARTED",
        "from": null,
        "to": {
          "lifecycleStatus": "TRIALING",
          "cancelAtPeriodEnd": false,
          "complimentary": false,
          "paymentStatus": "CURRENT",
          "accessState": "FULL"
        },
        "actorType": "SYSTEM",
        "reference": null
      }
    ],
    "payments": [
      {
        "id": 9003,
        "reference": "sub-l-12-rn-1-1",
        "purpose": "RENEWAL",
        "status": "FAILED",
        "amountPesewas": 39900,
        "currency": "GHS",
        "attemptNo": 1,
        "environment": "LIVE",
        "planCode": "GROWTH",
        "interval": "MONTHLY",
        "periodStart": "2027-01-11T09:30:00Z",
        "periodEnd": "2027-02-11T09:30:00Z",
        "createdAt": "2027-01-11T09:30:00Z",
        "paidAt": null,
        "verifiedAt": null,
        "feesPesewas": null,
        "failureCode": "INSUFFICIENT_FUNDS",
        "flagged": false,
        "flagReason": null,
        "refundedPesewas": 0,
        "pendingRefundPesewas": 0,
        "refundableRemainingPesewas": 0,
        "refundableUntil": null,
        "documents": []
      },
      {
        "id": 9002,
        "reference": "sub-l-12-up-1-1",
        "purpose": "UPGRADE",
        "status": "SUCCESS",
        "amountPesewas": 40645,
        "currency": "GHS",
        "attemptNo": 1,
        "environment": "LIVE",
        "planCode": "SCALE",
        "interval": "MONTHLY",
        "periodStart": "2026-12-11T09:30:00Z",
        "periodEnd": "2027-01-11T09:30:00Z",
        "createdAt": "2026-12-21T09:30:00Z",
        "paidAt": "2026-12-21T09:30:05Z",
        "verifiedAt": "2026-12-21T09:30:05Z",
        "feesPesewas": null,
        "failureCode": null,
        "flagged": false,
        "flagReason": null,
        "refundedPesewas": 0,
        "pendingRefundPesewas": 0,
        "refundableRemainingPesewas": 40645,
        "refundableUntil": "2027-03-21T09:30:05Z",
        "documents": [
          {
            "id": 503,
            "type": "INVOICE",
            "number": "SUB-2026-000002"
          },
          {
            "id": 504,
            "type": "RECEIPT",
            "number": "REC-2026-000002"
          }
        ]
      },
      {
        "id": 9001,
        "reference": "sub-l-12-tc-0-1",
        "purpose": "TRIAL_CONVERSION",
        "status": "SUCCESS",
        "amountPesewas": 39900,
        "currency": "GHS",
        "attemptNo": 1,
        "environment": "LIVE",
        "planCode": "GROWTH",
        "interval": "MONTHLY",
        "periodStart": "2026-12-11T09:30:00Z",
        "periodEnd": "2027-01-11T09:30:00Z",
        "createdAt": "2026-12-11T09:30:00Z",
        "paidAt": "2026-12-11T09:30:07Z",
        "verifiedAt": "2026-12-11T09:30:07Z",
        "feesPesewas": null,
        "failureCode": null,
        "flagged": false,
        "flagReason": null,
        "refundedPesewas": 10000,
        "pendingRefundPesewas": 0,
        "refundableRemainingPesewas": 29900,
        "refundableUntil": "2027-03-11T09:30:07Z",
        "documents": [
          {
            "id": 501,
            "type": "INVOICE",
            "number": "SUB-2026-000001"
          },
          {
            "id": 502,
            "type": "RECEIPT",
            "number": "REC-2026-000001"
          }
        ]
      }
    ],
    "documents": [
      {
        "id": 504,
        "type": "RECEIPT",
        "number": "REC-2026-000002"
      },
      {
        "id": 503,
        "type": "INVOICE",
        "number": "SUB-2026-000002"
      },
      {
        "id": 502,
        "type": "RECEIPT",
        "number": "REC-2026-000001"
      },
      {
        "id": 501,
        "type": "INVOICE",
        "number": "SUB-2026-000001"
      },
      {
        "id": 507,
        "type": "CREDIT_NOTE",
        "number": "CN-2026-000001"
      }
    ],
    "adjustments": [
      {
        "id": 7001,
        "type": "REFUND",
        "amountPesewas": -10000,
        "paymentId": 9001,
        "reason": "Customer overcharged during onboarding",
        "actorEmail": "admin@shopfi.test",
        "createdAt": "2026-12-30T10:00:05Z"
      }
    ],
    "recentAudit": [
      {
        "id": 9112,
        "occurredAt": "2027-01-11T09:30:04Z",
        "actorType": "SYSTEM",
        "actorId": null,
        "actorEmail": null,
        "tenantId": 12,
        "tenantSlug": "acme",
        "action": "PAYMENT_FAILED",
        "reference": "sub-l-12-rn-1-1",
        "reason": null,
        "before": {
          "lifecycleStatus": "ACTIVE",
          "paymentStatus": "CURRENT",
          "accessState": "FULL"
        },
        "after": {
          "lifecycleStatus": "PAST_DUE",
          "paymentStatus": "RETRYING",
          "accessState": "GRACE"
        }
      },
      {
        "id": 9087,
        "occurredAt": "2026-12-30T10:00:05Z",
        "actorType": "PLATFORM_ADMIN",
        "actorId": 1,
        "actorEmail": "admin@shopfi.test",
        "tenantId": 12,
        "tenantSlug": "acme",
        "action": "REFUND_COMPLETED",
        "reference": "sub-l-12-tc-0-1",
        "reason": "Customer overcharged during onboarding",
        "before": {
          "refundedPesewas": 0
        },
        "after": {
          "refundedPesewas": 10000
        }
      }
    ]
  }
}
```

<!-- example: action-result-extend-trial response SubscriptionDetailEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "tenant": {
      "id": 12,
      "slug": "acme",
      "displayName": "Acme Perfumes",
      "ownerEmail": "owner@acme.example"
    },
    "plan": {
      "id": 2,
      "code": "GROWTH",
      "name": "Growth",
      "monthlyPricePesewas": 39900,
      "annualPricePesewas": 383040
    },
    "interval": "MONTHLY",
    "state": {
      "lifecycleStatus": "TRIALING",
      "cancelAtPeriodEnd": false,
      "complimentary": false,
      "paymentStatus": "CURRENT",
      "accessState": "FULL"
    },
    "billingMode": "PAYSTACK",
    "environment": "LIVE",
    "manualRestriction": false,
    "trialEndsAt": "2027-01-27T10:00:00Z",
    "currentPeriod": null,
    "anchorAt": "2026-12-11T09:30:00Z",
    "nextBillingAt": "2027-01-27T10:00:00Z",
    "lockedPricesPesewas": {
      "MONTHLY": 39900,
      "ANNUAL": 383040
    },
    "grace": null,
    "pendingChange": null,
    "priceChange": null,
    "complimentary": null,
    "card": {
      "brand": "visa",
      "last4": "4081",
      "expMonth": 12,
      "expYear": 2029,
      "bank": "Test Bank",
      "expiringSoon": false
    },
    "version": 3,
    "timeline": [],
    "payments": [],
    "documents": [],
    "adjustments": [],
    "recentAudit": []
  }
}
```

<!-- example: action-not-allowed response ActionNotAllowedEnvelope 409 -->
```json
{
  "description": "This action is not allowed for the subscription's current state.",
  "data": {
    "code": "ACTION_NOT_ALLOWED",
    "action": "EXTEND_TRIAL",
    "lifecycleStatus": "ACTIVE",
    "accessState": "FULL"
  }
}
```

<!-- example: action-billing-managed response BillingManagedEnvelope 409 -->
```json
{
  "description": "This store is billed through Paystack. Use the billing actions.",
  "data": {
    "code": "BILLING_MANAGED"
  }
}
```

### 7.12 Existing tenants (O4, O22, M11)

Tenants that exist before billing (backfilled `TRIALING` with `trial_ends_at = now + 14 days`, or set `ACTIVE` / `CANCELED` by hand) get `billingMode = MANUAL`, `billingManaged = false`: they keep working under the pre-billing rule, have no card, are never charged and never enter the retry machinery. Their summary shows `billingMode MANUAL`, `card null` and (while trialing) the `TRIAL_NO_CARD` banner asking for a card (example `summary-manual-trial`, 9.1).
When a `MANUAL` member verifies a card (9.5) the tenant converts to `PAYSTACK` (E19) with its current `trialEndsAt` kept (a `TRIALING` one will be charged at that date); a `MANUAL` `ACTIVE` tenant subscribes with `POST /subscribe` (7.5, charged now). A `MANUAL` trial that reaches its end WITHOUT a card enters the failure policy with reason `NO_CARD` (E20: grace and reminders, zero charge attempts; a card added during grace triggers one immediate charge, 8.7). The PLAN-01 bridge keeps working for `MANUAL` tenants and is refused with `409 BILLING_MANAGED` for `PAYSTACK` ones (O22).

## 8. Failed payments: retries, grace, restriction, restore (D2, FR-13, FR-14)

### 8.1 One policy

The first charge after the trial, a renewal and a member "retry now" share ONE policy driven by the settings of section 4 (`graceDays`, `retryOffsetsDays`, `retryTimeOfDayUtc`). Defaults: 7 days of grace, retries on days 1, 3 and 5 after the first failure, full access with reminders during grace, then `READ_ONLY` for paid features until settled, access returns automatically on payment.

### 8.2 Schedule (snapshot at the first failure)

At the first failure `F` (attempt 1 failed): E5, `graceStartedAt = F`, `graceEndsAt = F + graceDays days`, and the retry schedule is computed and stored: for each offset `o` the attempt instant is `F + o days` with the time of day of `F` (or `retryTimeOfDayUtc` when set), **clamped to `graceEndsAt`** (an attempt is never later than the grace end; the job processes retries BEFORE the grace expiry check of the same round). Attempts are numbered 2, 3, ...; every attempt inserts its own payment row `sub-<env>-<tenantId>-rn-<periodIndex>-<attempt>` before calling Paystack. Later settings edits change only future failures.

| failedAt | graceDays | offsets | timeOfDay | attempts 2..n | graceEndsAt |
|---|---|---|---|---|---|
| `2027-01-11T09:30:00Z` | 7 | `1,3,5` | - | 2027-01-12T09:30:00Z, 2027-01-14T09:30:00Z, 2027-01-16T09:30:00Z | `2027-01-18T09:30:00Z` |
| `2027-01-11T09:30:00Z` | 7 | `1,3,5` | 06:00 | 2027-01-12T06:00:00Z, 2027-01-14T06:00:00Z, 2027-01-16T06:00:00Z | `2027-01-18T09:30:00Z` |
| `2027-01-11T09:30:00Z` | 10 | `1,3,5,8` | 23:00 | 2027-01-12T23:00:00Z, 2027-01-14T23:00:00Z, 2027-01-16T23:00:00Z, 2027-01-19T23:00:00Z | `2027-01-21T09:30:00Z` |
| `2027-01-11T09:30:00Z` | 7 | `7` | 23:00 | 2027-01-18T09:30:00Z | `2027-01-18T09:30:00Z` |
| `2027-01-31T18:45:10Z` | 7 | `1,3,5` | - | 2027-02-01T18:45:10Z, 2027-02-03T18:45:10Z, 2027-02-05T18:45:10Z | `2027-02-07T18:45:10Z` |

### 8.3 What "full access with reminders" and "read-only until settled" mean

- `GRACE`: `readOnly` is `false`, every feature of the plan works, no 402 appears; the member sees the `PAYMENT_FAILED_GRACE` banner (ADMIN and FRONT_DESK) and gets the dunning e-mails of section 16 (each once per attempt).
- `READ_ONLY` (after `graceEndsAt`, job E7): `readOnly` is `true` exactly as [plans.md](plans.md) sections 13 and 14: reads of gated routes keep working, gated writes and limit-checked creates answer `402 PLAN_UPGRADE_REQUIRED` reason `SUBSCRIPTION_INACTIVE`; the storefront, checkout, orders and payments of the shop, auth, platform and billing endpoints keep working (never gated, section 17.2). The cache is evicted at once so the very next request is read-only.

### 8.4 Retries, no double charge

The retry job charges the stored authorization at each scheduled attempt: success E8 (`ACTIVE`, `CURRENT`, `FULL`, `grace` cleared, invoice and receipt, `AccessRestored` if it was `READ_ONLY`); failure E6 and the next attempt. Never two attempts for the same (subscription, period, attempt number); never an attempt while a payment is unresolved (`PENDING` is resolved by `verify` first); a non-retryable decline (`AUTHORIZATION_REVOKED`, `CARD_EXPIRED`) skips the remaining card retries (payment `FAILED`, access `GRACE` continues to the end, the banner and mail ask for a new card).
Under every interleaving of scheduled retry, member retry, webhook, reconciliation and admin action: at most one `SUCCESS` per (subscription, period), total charged <= the period price (property test, 200 randomized interleavings, BILL-04 AC6).

### 8.5 Automatic restore

Any VERIFIED success while `GRACE` or `READ_ONLY` (scheduled retry, member retry, webhook, reconciliation, admin-recorded) returns access to `FULL` in the same transaction (E8), clears the grace fields, evicts the cache and queues `ACCESS_RESTORED`. A late `charge.success` webhook after the grace expiry restores too. A payment for an old, superseded period is recorded but does not extend access beyond its own period. A `manualRestriction` is not lifted by a payment.

### 8.6 Member: update the card and retry now (FR-13)

`POST /api/v1/admin/billing/card/initialize` then `POST .../card/verify` replace the default authorization (the old one is marked `REPLACED`, never deleted). If the subscription is `PAST_DUE` the verify also starts ONE immediate charge attempt (the next attempt number, throttled; `409 PAYMENT_PENDING` while one is in flight) and returns it as `retryPayment`. `POST /api/v1/admin/billing/retry-payment` does the same with the card already on file (409 `CHANGE_NOT_ALLOWED` reason `NOTHING_DUE` when the subscription is not `PAST_DUE`; at most 3 per 10 minutes, minimum 60 seconds apart). Success restores access (8.5).

### 8.7 No card (`NO_CARD`, O4, O13)

A subscription without a usable card at the moment a charge is due (a `MANUAL` trial that ended, a complimentary term that ended without a card) enters `PAST_DUE` with `grace.reason = NO_CARD`: payment `FAILED` from the start, access `GRACE`, the reminders ask to add a card, ZERO charge attempts are made; at `graceEndsAt` access becomes `READ_ONLY` as above. A card verified during the grace triggers one immediate charge (8.6).

### 8.8 A plan-change charge that fails never starts grace

A failed or unknown upgrade or new-subscription charge (7.5, 7.6) never starts grace and never changes the plan or the state (BR-07, BR-04, O24); only a failed renewal or trial conversion does.

## 9. Member billing API (`/api/v1/admin/billing/**`, FR-04, FR-07, FR-10..13, FR-17..21)

Tenant admin routes. The tenant is ALWAYS the request's resolved tenant (`TenantContext`), never a parameter, so only the caller's own subscription, card, payments and documents exist for the caller (S5). Anonymous, foreign-tenant and platform tokens: `401`; `CUSTOMER`: `403`;
`FRONT_DESK`: `403` everywhere except `GET /summary` (banner subset); suspended tenant: `423` (tenant resolution: the whole-store lock, M14; a suspended tenant cannot pay until the platform reactivates it). All money actions are ADMIN only (S5). Never plan-gated, never `402`, available in `GRACE` and `READ_ONLY` (section 17.2).
An id (payment, document) that belongs to another tenant answers `404` with the SAME body as a missing one.

<!-- example: billing-record-not-found response ErrorEnvelope 404 -->
```json
{
  "description": "Billing record not found",
  "data": null
}
```

### 9.1 `GET /api/v1/admin/billing/summary`

One call for the Billing area and the admin-shell banners. `data` is `BillingSummary` for an ADMIN (`view: "FULL"`) and `BillingBannerView` for a FRONT_DESK (`view: "BANNER"`, accessState and the non-financial banners only). All values are server-computed; the client formats dates and money and does no maths beyond display.

| Key | Meaning |
|---|---|
| `serverTime` | the server clock, so the client computes "N days left" without clock skew |
| `billingEnabled` | the platform master switch. `false`: the platform behaves as before BILL; the area shows the plan panel only |
| `billingMode`, `state` | `PAYSTACK` / `MANUAL`; the three independent fields of section 3 (`lifecycleStatus`, `cancelAtPeriodEnd`, `complimentary`, `paymentStatus`, `accessState`) |
| `plan`, `interval` | the current plan (`id`, `code`, `name`, both prices; catalog values) and billing interval |
| `trialEndsAt`, `currentPeriod`, `cancelEffectiveAt`, `complimentaryUntil` | the dates; `currentPeriod` is `null` while trialing and when ended |
| `nextBilling` | what will be charged next: `{at, amountPesewas, planCode, interval, reason}` (`TRIAL_CONVERSION`, `RENEWAL`, `RETRY`); `null` when nothing will be charged (cancellation scheduled, complimentary, ended, `MANUAL`) |
| `lockedPricePesewas` | the price lock of the current interval (6.1) |
| `grace` | `{startedAt, endsAt, nextRetryAt, retriesRemaining, reason}` while `PAST_DUE`; `nextRetryAt` `null` when no attempt is scheduled |
| `pendingChange` | `{kind, planId, planCode, planName, interval, effectiveAt, pricePesewas}` or `null` |
| `priceChange` | the price change notice (6.4) or `null` |
| `card` | display data only (`brand`, `last4`, `expMonth`, `expYear`, `bank`, `expiringSoon`) or `null`; never the authorization code (S1) |
| `actions` | capability flags: `canChangePlan` (PAYSTACK, `TRIALING` or `ACTIVE`, not complimentary, nothing in flight, not `PAST_DUE`), `canCancel` (live, not already scheduled, not complimentary), `canReactivate` (scheduled and live), `canUpdateCard` (billing enabled), `canRetryPayment` (`PAST_DUE`, card on file, nothing in flight), `canSubscribe` (ended, or `MANUAL` with a card). The endpoint stays the authority |
| `banners` | prioritized banner codes (9.3) |
| `version` | the subscription version (informational for members) |

<!-- example: summary-trialing response BillingSummaryEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "view": "FULL",
    "serverTime": "2026-12-01T09:30:00Z",
    "billingEnabled": true,
    "billingMode": "PAYSTACK",
    "plan": {
      "id": 2,
      "code": "GROWTH",
      "name": "Growth",
      "monthlyPricePesewas": 39900,
      "annualPricePesewas": 383040
    },
    "interval": "MONTHLY",
    "state": {
      "lifecycleStatus": "TRIALING",
      "cancelAtPeriodEnd": false,
      "complimentary": false,
      "paymentStatus": "CURRENT",
      "accessState": "FULL"
    },
    "trialEndsAt": "2026-12-11T09:30:00Z",
    "currentPeriod": null,
    "cancelEffectiveAt": null,
    "complimentaryUntil": null,
    "nextBilling": {
      "at": "2026-12-11T09:30:00Z",
      "amountPesewas": 39900,
      "planCode": "GROWTH",
      "interval": "MONTHLY",
      "reason": "TRIAL_CONVERSION"
    },
    "lockedPricePesewas": 39900,
    "grace": null,
    "pendingChange": null,
    "priceChange": null,
    "card": {
      "brand": "visa",
      "last4": "4081",
      "expMonth": 12,
      "expYear": 2029,
      "bank": "Test Bank",
      "expiringSoon": false
    },
    "actions": {
      "canChangePlan": true,
      "canCancel": true,
      "canReactivate": false,
      "canUpdateCard": true,
      "canRetryPayment": false,
      "canSubscribe": false
    },
    "banners": [],
    "version": 3
  }
}
```

<!-- example: summary-trial-ending response BillingSummaryEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "view": "FULL",
    "serverTime": "2026-12-08T09:30:00Z",
    "billingEnabled": true,
    "billingMode": "PAYSTACK",
    "plan": {
      "id": 2,
      "code": "GROWTH",
      "name": "Growth",
      "monthlyPricePesewas": 39900,
      "annualPricePesewas": 383040
    },
    "interval": "MONTHLY",
    "state": {
      "lifecycleStatus": "TRIALING",
      "cancelAtPeriodEnd": false,
      "complimentary": false,
      "paymentStatus": "CURRENT",
      "accessState": "FULL"
    },
    "trialEndsAt": "2026-12-11T09:30:00Z",
    "currentPeriod": null,
    "cancelEffectiveAt": null,
    "complimentaryUntil": null,
    "nextBilling": {
      "at": "2026-12-11T09:30:00Z",
      "amountPesewas": 39900,
      "planCode": "GROWTH",
      "interval": "MONTHLY",
      "reason": "TRIAL_CONVERSION"
    },
    "lockedPricePesewas": 39900,
    "grace": null,
    "pendingChange": null,
    "priceChange": null,
    "card": {
      "brand": "visa",
      "last4": "4081",
      "expMonth": 12,
      "expYear": 2029,
      "bank": "Test Bank",
      "expiringSoon": false
    },
    "actions": {
      "canChangePlan": true,
      "canCancel": true,
      "canReactivate": false,
      "canUpdateCard": true,
      "canRetryPayment": false,
      "canSubscribe": false
    },
    "banners": [
      {
        "kind": "TRIAL_ENDING",
        "severity": "INFO",
        "dismissible": true,
        "params": {
          "trialEndsAt": "2026-12-11T09:30:00Z",
          "daysLeft": 3,
          "cardOnFile": true,
          "amountPesewas": 39900
        }
      }
    ],
    "version": 3
  }
}
```

<!-- example: summary-active-pending-downgrade response BillingSummaryEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "view": "FULL",
    "serverTime": "2026-12-21T09:31:00Z",
    "billingEnabled": true,
    "billingMode": "PAYSTACK",
    "plan": {
      "id": 3,
      "code": "SCALE",
      "name": "Scale",
      "monthlyPricePesewas": 99900,
      "annualPricePesewas": 959040
    },
    "interval": "MONTHLY",
    "state": {
      "lifecycleStatus": "ACTIVE",
      "cancelAtPeriodEnd": false,
      "complimentary": false,
      "paymentStatus": "CURRENT",
      "accessState": "FULL"
    },
    "trialEndsAt": null,
    "currentPeriod": {
      "start": "2026-12-11T09:30:00Z",
      "end": "2027-01-11T09:30:00Z"
    },
    "cancelEffectiveAt": null,
    "complimentaryUntil": null,
    "nextBilling": {
      "at": "2027-01-11T09:30:00Z",
      "amountPesewas": 39900,
      "planCode": "GROWTH",
      "interval": "MONTHLY",
      "reason": "RENEWAL"
    },
    "lockedPricePesewas": 99900,
    "grace": null,
    "pendingChange": {
      "kind": "DOWNGRADE",
      "planId": 2,
      "planCode": "GROWTH",
      "planName": "Growth",
      "interval": "MONTHLY",
      "effectiveAt": "2027-01-11T09:30:00Z",
      "pricePesewas": 39900
    },
    "priceChange": null,
    "card": {
      "brand": "visa",
      "last4": "4081",
      "expMonth": 12,
      "expYear": 2029,
      "bank": "Test Bank",
      "expiringSoon": false
    },
    "actions": {
      "canChangePlan": true,
      "canCancel": true,
      "canReactivate": false,
      "canUpdateCard": true,
      "canRetryPayment": false,
      "canSubscribe": false
    },
    "banners": [
      {
        "kind": "INVOICE_AVAILABLE",
        "severity": "INFO",
        "dismissible": true,
        "params": {
          "documentId": 503,
          "documentType": "INVOICE"
        }
      }
    ],
    "version": 7
  }
}
```

<!-- example: summary-cancel-scheduled response BillingSummaryEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "view": "FULL",
    "serverTime": "2027-01-02T10:00:00Z",
    "billingEnabled": true,
    "billingMode": "PAYSTACK",
    "plan": {
      "id": 2,
      "code": "GROWTH",
      "name": "Growth",
      "monthlyPricePesewas": 39900,
      "annualPricePesewas": 383040
    },
    "interval": "MONTHLY",
    "state": {
      "lifecycleStatus": "ACTIVE",
      "cancelAtPeriodEnd": true,
      "complimentary": false,
      "paymentStatus": "CURRENT",
      "accessState": "FULL"
    },
    "trialEndsAt": null,
    "currentPeriod": {
      "start": "2026-12-11T09:30:00Z",
      "end": "2027-01-11T09:30:00Z"
    },
    "cancelEffectiveAt": "2027-01-11T09:30:00Z",
    "complimentaryUntil": null,
    "nextBilling": null,
    "lockedPricePesewas": 39900,
    "grace": null,
    "pendingChange": null,
    "priceChange": null,
    "card": {
      "brand": "visa",
      "last4": "4081",
      "expMonth": 12,
      "expYear": 2029,
      "bank": "Test Bank",
      "expiringSoon": false
    },
    "actions": {
      "canChangePlan": true,
      "canCancel": false,
      "canReactivate": true,
      "canUpdateCard": true,
      "canRetryPayment": false,
      "canSubscribe": false
    },
    "banners": [
      {
        "kind": "CANCELLATION_SCHEDULED",
        "severity": "INFO",
        "dismissible": true,
        "params": {
          "effectiveAt": "2027-01-11T09:30:00Z"
        }
      }
    ],
    "version": 6
  }
}
```

<!-- example: summary-grace response BillingSummaryEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "view": "FULL",
    "serverTime": "2027-01-11T09:31:00Z",
    "billingEnabled": true,
    "billingMode": "PAYSTACK",
    "plan": {
      "id": 2,
      "code": "GROWTH",
      "name": "Growth",
      "monthlyPricePesewas": 39900,
      "annualPricePesewas": 383040
    },
    "interval": "MONTHLY",
    "state": {
      "lifecycleStatus": "PAST_DUE",
      "cancelAtPeriodEnd": false,
      "complimentary": false,
      "paymentStatus": "RETRYING",
      "accessState": "GRACE"
    },
    "trialEndsAt": null,
    "currentPeriod": {
      "start": "2026-12-11T09:30:00Z",
      "end": "2027-01-11T09:30:00Z"
    },
    "cancelEffectiveAt": null,
    "complimentaryUntil": null,
    "nextBilling": {
      "at": "2027-01-12T09:30:00Z",
      "amountPesewas": 39900,
      "planCode": "GROWTH",
      "interval": "MONTHLY",
      "reason": "RETRY"
    },
    "lockedPricePesewas": 39900,
    "grace": {
      "startedAt": "2027-01-11T09:30:00Z",
      "endsAt": "2027-01-18T09:30:00Z",
      "nextRetryAt": "2027-01-12T09:30:00Z",
      "retriesRemaining": 3,
      "reason": "PAYMENT_FAILED"
    },
    "pendingChange": null,
    "priceChange": null,
    "card": {
      "brand": "visa",
      "last4": "4081",
      "expMonth": 12,
      "expYear": 2029,
      "bank": "Test Bank",
      "expiringSoon": false
    },
    "actions": {
      "canChangePlan": false,
      "canCancel": true,
      "canReactivate": false,
      "canUpdateCard": true,
      "canRetryPayment": true,
      "canSubscribe": false
    },
    "banners": [
      {
        "kind": "PAYMENT_FAILED_GRACE",
        "severity": "WARNING",
        "dismissible": false,
        "params": {
          "graceEndsAt": "2027-01-18T09:30:00Z",
          "nextRetryAt": "2027-01-12T09:30:00Z",
          "amountPesewas": 39900
        }
      }
    ],
    "version": 9
  }
}
```

<!-- example: summary-read-only response BillingSummaryEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "view": "FULL",
    "serverTime": "2027-01-18T09:31:00Z",
    "billingEnabled": true,
    "billingMode": "PAYSTACK",
    "plan": {
      "id": 2,
      "code": "GROWTH",
      "name": "Growth",
      "monthlyPricePesewas": 39900,
      "annualPricePesewas": 383040
    },
    "interval": "MONTHLY",
    "state": {
      "lifecycleStatus": "PAST_DUE",
      "cancelAtPeriodEnd": false,
      "complimentary": false,
      "paymentStatus": "FAILED",
      "accessState": "READ_ONLY"
    },
    "trialEndsAt": null,
    "currentPeriod": {
      "start": "2026-12-11T09:30:00Z",
      "end": "2027-01-11T09:30:00Z"
    },
    "cancelEffectiveAt": null,
    "complimentaryUntil": null,
    "nextBilling": null,
    "lockedPricePesewas": 39900,
    "grace": {
      "startedAt": "2027-01-11T09:30:00Z",
      "endsAt": "2027-01-18T09:30:00Z",
      "nextRetryAt": null,
      "retriesRemaining": 0,
      "reason": "PAYMENT_FAILED"
    },
    "pendingChange": null,
    "priceChange": null,
    "card": {
      "brand": "visa",
      "last4": "4081",
      "expMonth": 12,
      "expYear": 2029,
      "bank": "Test Bank",
      "expiringSoon": false
    },
    "actions": {
      "canChangePlan": false,
      "canCancel": true,
      "canReactivate": false,
      "canUpdateCard": true,
      "canRetryPayment": true,
      "canSubscribe": false
    },
    "banners": [
      {
        "kind": "ACCESS_RESTRICTED",
        "severity": "CRITICAL",
        "dismissible": false,
        "params": {
          "graceEndsAt": "2027-01-18T09:30:00Z",
          "amountPesewas": 39900
        }
      }
    ],
    "version": 12
  }
}
```

<!-- example: summary-manual-trial response BillingSummaryEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "view": "FULL",
    "serverTime": "2026-12-01T09:30:00Z",
    "billingEnabled": true,
    "billingMode": "MANUAL",
    "plan": {
      "id": 2,
      "code": "GROWTH",
      "name": "Growth",
      "monthlyPricePesewas": 39900,
      "annualPricePesewas": 383040
    },
    "interval": "MONTHLY",
    "state": {
      "lifecycleStatus": "TRIALING",
      "cancelAtPeriodEnd": false,
      "complimentary": false,
      "paymentStatus": "NONE",
      "accessState": "FULL"
    },
    "trialEndsAt": "2026-12-11T09:30:00Z",
    "currentPeriod": null,
    "cancelEffectiveAt": null,
    "complimentaryUntil": null,
    "nextBilling": null,
    "lockedPricePesewas": 39900,
    "grace": null,
    "pendingChange": null,
    "priceChange": null,
    "card": null,
    "actions": {
      "canChangePlan": false,
      "canCancel": false,
      "canReactivate": false,
      "canUpdateCard": true,
      "canRetryPayment": false,
      "canSubscribe": false
    },
    "banners": [
      {
        "kind": "TRIAL_NO_CARD",
        "severity": "WARNING",
        "dismissible": false,
        "params": {
          "trialEndsAt": "2026-12-11T09:30:00Z",
          "daysLeft": 10,
          "cardOnFile": false
        }
      }
    ],
    "version": 1
  }
}
```

<!-- example: summary-ended response BillingSummaryEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "view": "FULL",
    "serverTime": "2027-01-15T10:00:00Z",
    "billingEnabled": true,
    "billingMode": "PAYSTACK",
    "plan": {
      "id": 2,
      "code": "GROWTH",
      "name": "Growth",
      "monthlyPricePesewas": 39900,
      "annualPricePesewas": 383040
    },
    "interval": "MONTHLY",
    "state": {
      "lifecycleStatus": "CANCELED",
      "cancelAtPeriodEnd": false,
      "complimentary": false,
      "paymentStatus": "NONE",
      "accessState": "READ_ONLY"
    },
    "trialEndsAt": null,
    "currentPeriod": {
      "start": "2026-12-11T09:30:00Z",
      "end": "2027-01-11T09:30:00Z"
    },
    "cancelEffectiveAt": null,
    "complimentaryUntil": null,
    "nextBilling": null,
    "lockedPricePesewas": 39900,
    "grace": null,
    "pendingChange": null,
    "priceChange": null,
    "card": {
      "brand": "visa",
      "last4": "4081",
      "expMonth": 12,
      "expYear": 2029,
      "bank": "Test Bank",
      "expiringSoon": false
    },
    "actions": {
      "canChangePlan": false,
      "canCancel": false,
      "canReactivate": false,
      "canUpdateCard": true,
      "canRetryPayment": false,
      "canSubscribe": true
    },
    "banners": [
      {
        "kind": "ACCESS_RESTRICTED",
        "severity": "CRITICAL",
        "dismissible": false,
        "params": {}
      }
    ],
    "version": 8
  }
}
```

<!-- example: summary-complimentary response BillingSummaryEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "view": "FULL",
    "serverTime": "2027-01-15T10:00:00Z",
    "billingEnabled": true,
    "billingMode": "PAYSTACK",
    "plan": {
      "id": 3,
      "code": "SCALE",
      "name": "Scale",
      "monthlyPricePesewas": 99900,
      "annualPricePesewas": 959040
    },
    "interval": "MONTHLY",
    "state": {
      "lifecycleStatus": "ACTIVE",
      "cancelAtPeriodEnd": false,
      "complimentary": true,
      "paymentStatus": "NONE",
      "accessState": "FULL"
    },
    "trialEndsAt": null,
    "currentPeriod": null,
    "cancelEffectiveAt": null,
    "complimentaryUntil": "2027-06-30T23:59:59Z",
    "nextBilling": null,
    "lockedPricePesewas": 99900,
    "grace": null,
    "pendingChange": null,
    "priceChange": null,
    "card": null,
    "actions": {
      "canChangePlan": false,
      "canCancel": false,
      "canReactivate": false,
      "canUpdateCard": true,
      "canRetryPayment": false,
      "canSubscribe": false
    },
    "banners": [],
    "version": 5
  }
}
```

<!-- example: summary-billing-not-enabled response BillingSummaryEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "view": "FULL",
    "serverTime": "2026-11-20T09:30:00Z",
    "billingEnabled": false,
    "billingMode": "MANUAL",
    "plan": {
      "id": 2,
      "code": "GROWTH",
      "name": "Growth",
      "monthlyPricePesewas": 39900,
      "annualPricePesewas": 383040
    },
    "interval": "MONTHLY",
    "state": {
      "lifecycleStatus": "TRIALING",
      "cancelAtPeriodEnd": false,
      "complimentary": false,
      "paymentStatus": "NONE",
      "accessState": "FULL"
    },
    "trialEndsAt": "2026-12-11T09:30:00Z",
    "currentPeriod": null,
    "cancelEffectiveAt": null,
    "complimentaryUntil": null,
    "nextBilling": null,
    "lockedPricePesewas": 39900,
    "grace": null,
    "pendingChange": null,
    "priceChange": null,
    "card": null,
    "actions": {
      "canChangePlan": false,
      "canCancel": false,
      "canReactivate": false,
      "canUpdateCard": false,
      "canRetryPayment": false,
      "canSubscribe": false
    },
    "banners": [],
    "version": 0
  }
}
```

The FRONT_DESK view (same endpoint; only non-financial banner kinds, params limited to dates and `daysLeft`; no plan, price, card or payment data):

<!-- example: summary-front-desk response BillingSummaryEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "view": "BANNER",
    "serverTime": "2027-01-11T09:31:00Z",
    "accessState": "GRACE",
    "banners": [
      {
        "kind": "PAYMENT_FAILED_GRACE",
        "severity": "WARNING",
        "dismissible": false,
        "params": {
          "graceEndsAt": "2027-01-18T09:30:00Z"
        }
      }
    ]
  }
}
```

### 9.3 Banners

The banners are codes and values, never display text; the frontend owns the copy (BILL-FE-02b AC6). Priority order (highest first, at most 10): `ACCESS_RESTRICTED`, `PAYMENT_FAILED_GRACE`, `PAYMENT_PENDING`, `TRIAL_NO_CARD`, `TRIAL_ENDING`, `CARD_EXPIRING`, `PRICE_CHANGE_NOTICE`, `CANCELLATION_SCHEDULED`, `COMPLIMENTARY_ENDING`, `INVOICE_AVAILABLE`.

| `kind` | Present when | `severity` | `dismissible` | `params` (only these) | FRONT_DESK |
|---|---|---|---|---|---|
| `ACCESS_RESTRICTED` | `accessState = READ_ONLY` from billing (grace over, ended, manual restriction) | `CRITICAL` | no | `graceEndsAt?`, `amountPesewas?` (owed) | yes: `graceEndsAt` only |
| `PAYMENT_FAILED_GRACE` | `accessState = GRACE` | `WARNING` | no | `graceEndsAt`, `nextRetryAt`, `amountPesewas` | yes: `graceEndsAt` only |
| `PAYMENT_PENDING` | `paymentStatus = PENDING` | `INFO` | no | `amountPesewas` | no |
| `TRIAL_NO_CARD` | `TRIALING` in `MANUAL` mode without a card | `WARNING` | no | `trialEndsAt`, `daysLeft`, `cardOnFile` (false) | no |
| `TRIAL_ENDING` | `TRIALING` and within the largest `trialReminderDaysBefore` window | `INFO` | session | `trialEndsAt`, `daysLeft`, `cardOnFile`, `amountPesewas` (the first charge) | yes: `trialEndsAt`, `daysLeft` |
| `CARD_EXPIRING` | the default card expires this or next month | `WARNING` | session | `expMonth`, `expYear`, `last4` | no |
| `PRICE_CHANGE_NOTICE` | a `priceChange` exists | `INFO` | session | `oldPricePesewas`, `newPricePesewas`, `effectiveAt` (the expected `appliesAt`) | no |
| `CANCELLATION_SCHEDULED` | `cancelAtPeriodEnd` | `INFO` | session | `effectiveAt` | no |
| `COMPLIMENTARY_ENDING` | complimentary with an `until` within the largest `renewalReminderDaysBefore` window | `INFO` | session | `endsAt` | no |
| `INVOICE_AVAILABLE` | a document was issued in the last 7 days | `INFO` | session | `documentId`, `documentType` | no |

`daysLeft` = whole days remaining, rounded down (`floor(remainingSeconds / 86400)`), never negative. "Session" dismissible = the client may hide it for the browser session only; `dismissible: false` banners (restriction, grace, no card, pending payment) are never dismissible.

### 9.5 Card management (FR-07, FR-13)

The card is authorized by Paystack's hosted checkout; the browser never sends card data to us (S1). `GET /api/v1/admin/billing/card` returns the default card's display data and the mode.

<!-- example: card-view response CardViewEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "card": {
      "brand": "visa",
      "last4": "4081",
      "expMonth": 12,
      "expYear": 2029,
      "bank": "Test Bank",
      "expiringSoon": false
    },
    "billingMode": "PAYSTACK"
  }
}
```

<!-- example: card-view-none response CardViewEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "card": null,
    "billingMode": "MANUAL"
  }
}
```

`POST /api/v1/admin/billing/card/initialize` (ADMIN, throttled per tenant; no body): creates the verification transaction for the tenant OWNER e-mail of the master record, `channels = allowedChannels`, `amount = cardVerificationAmountPesewas`, a fresh `sub-card-...` reference stored against the tenant, a callback URL built from configuration for the tenant's admin origin (`/admin/settings/billing/card-callback`); returns `{checkoutUrl, reference, verificationAmountPesewas, currency, expiresAt}`.

<!-- example: member-card-initialize-response response CardCheckoutInitEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "checkoutUrl": "https://checkout.paystack.com/9xk2m4q7rt",
    "reference": "sub-card-l-b7n3v1c5x9z2a4s6d8f0g2h4",
    "verificationAmountPesewas": 100,
    "currency": "GHS",
    "expiresAt": "2027-01-12T09:45:00Z"
  }
}
```

`POST /api/v1/admin/billing/card/verify` `{reference}`: server-side `verify` and the same acceptance rules as 5.4 (the reference is one this tenant initialized, success, amount, GHS, reusable, channel in `recurringChannels`, customer e-mail = the owner e-mail). It stores the new authorization (encrypted), marks the old one `REPLACED` (never deleted), refunds the hold at once (`verificationRefunded`), converts a `MANUAL` tenant to `PAYSTACK` (E19), and, when the subscription is `PAST_DUE`, starts ONE immediate charge attempt (`retryPayment`, 8.6). The `card_expiring` notice fires at most once per card.
Failures: `400 CARD_AUTH_FAILED` (`retryAllowed`, `reason`), `404` for a reference that is not this tenant's, `409 BILLING_NOT_CONFIGURED`, `409 PAYMENT_PENDING` (a retry is already in flight), `503 PROVIDER_UNAVAILABLE`.

<!-- example: card-verify-request valid CardVerifyRequest -->
```json
{
  "reference": "sub-card-l-b7n3v1c5x9z2a4s6d8f0g2h4"
}
```

<!-- example: card-verify-request-bad-reference invalid CardVerifyRequest 400 -->
```json
{
  "reference": "some reference"
}
```

<!-- example: card-verify-result response CardVerifyResultEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "card": {
      "brand": "visa",
      "last4": "4081",
      "expMonth": 12,
      "expYear": 2029,
      "bank": "Test Bank",
      "expiringSoon": false
    },
    "verificationRefunded": true,
    "retryPayment": null,
    "summary": {
      "view": "FULL",
      "serverTime": "2027-01-02T10:00:00Z",
      "billingEnabled": true,
      "billingMode": "PAYSTACK",
      "plan": {
        "id": 2,
        "code": "GROWTH",
        "name": "Growth",
        "monthlyPricePesewas": 39900,
        "annualPricePesewas": 383040
      },
      "interval": "MONTHLY",
      "state": {
        "lifecycleStatus": "ACTIVE",
        "cancelAtPeriodEnd": true,
        "complimentary": false,
        "paymentStatus": "CURRENT",
        "accessState": "FULL"
      },
      "trialEndsAt": null,
      "currentPeriod": {
        "start": "2026-12-11T09:30:00Z",
        "end": "2027-01-11T09:30:00Z"
      },
      "cancelEffectiveAt": "2027-01-11T09:30:00Z",
      "complimentaryUntil": null,
      "nextBilling": null,
      "lockedPricePesewas": 39900,
      "grace": null,
      "pendingChange": null,
      "priceChange": null,
      "card": {
        "brand": "visa",
        "last4": "4081",
        "expMonth": 12,
        "expYear": 2029,
        "bank": "Test Bank",
        "expiringSoon": false
      },
      "actions": {
        "canChangePlan": true,
        "canCancel": false,
        "canReactivate": true,
        "canUpdateCard": true,
        "canRetryPayment": false,
        "canSubscribe": false
      },
      "banners": [
        {
          "kind": "CANCELLATION_SCHEDULED",
          "severity": "INFO",
          "dismissible": true,
          "params": {
            "effectiveAt": "2027-01-11T09:30:00Z"
          }
        }
      ],
      "version": 7
    }
  }
}
```

<!-- example: card-verify-result-retry-started response CardVerifyResultEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "card": {
      "brand": "visa",
      "last4": "4081",
      "expMonth": 12,
      "expYear": 2029,
      "bank": "Test Bank",
      "expiringSoon": false
    },
    "verificationRefunded": true,
    "retryPayment": {
      "id": 9005,
      "reference": "sub-l-12-rn-1-2",
      "purpose": "RETRY",
      "status": "PENDING",
      "amountPesewas": 39900,
      "currency": "GHS",
      "attemptNo": 2,
      "planCode": "GROWTH",
      "planName": "Growth",
      "interval": "MONTHLY",
      "periodStart": "2027-01-11T09:30:00Z",
      "periodEnd": "2027-02-11T09:30:00Z",
      "createdAt": "2027-01-12T09:15:00Z",
      "paidAt": null,
      "failureCode": null,
      "refundedPesewas": 0,
      "documents": []
    },
    "summary": {
      "view": "FULL",
      "serverTime": "2027-01-12T09:15:30Z",
      "billingEnabled": true,
      "billingMode": "PAYSTACK",
      "plan": {
        "id": 2,
        "code": "GROWTH",
        "name": "Growth",
        "monthlyPricePesewas": 39900,
        "annualPricePesewas": 383040
      },
      "interval": "MONTHLY",
      "state": {
        "lifecycleStatus": "PAST_DUE",
        "cancelAtPeriodEnd": false,
        "complimentary": false,
        "paymentStatus": "PENDING",
        "accessState": "GRACE"
      },
      "trialEndsAt": null,
      "currentPeriod": {
        "start": "2026-12-11T09:30:00Z",
        "end": "2027-01-11T09:30:00Z"
      },
      "cancelEffectiveAt": null,
      "complimentaryUntil": null,
      "nextBilling": {
        "at": "2027-01-12T09:30:00Z",
        "amountPesewas": 39900,
        "planCode": "GROWTH",
        "interval": "MONTHLY",
        "reason": "RETRY"
      },
      "lockedPricePesewas": 39900,
      "grace": {
        "startedAt": "2027-01-11T09:30:00Z",
        "endsAt": "2027-01-18T09:30:00Z",
        "nextRetryAt": "2027-01-12T09:30:00Z",
        "retriesRemaining": 3,
        "reason": "PAYMENT_FAILED"
      },
      "pendingChange": null,
      "priceChange": null,
      "card": {
        "brand": "visa",
        "last4": "4081",
        "expMonth": 12,
        "expYear": 2029,
        "bank": "Test Bank",
        "expiringSoon": false
      },
      "actions": {
        "canChangePlan": false,
        "canCancel": true,
        "canReactivate": false,
        "canUpdateCard": true,
        "canRetryPayment": false,
        "canSubscribe": false
      },
      "banners": [],
      "version": 11
    }
  }
}
```

<!-- example: card-verify-failed response CardAuthFailedEnvelope 400 -->
```json
{
  "description": "We could not verify this card. Try another card.",
  "data": {
    "code": "CARD_AUTH_FAILED",
    "retryAllowed": true,
    "reason": "ABANDONED"
  }
}
```

`POST /api/v1/admin/billing/retry-payment` (no body, ADMIN, throttled): `202` with the pending attempt; `409 PAYMENT_PENDING` while one is in flight; `409 CARD_REQUIRED` without a usable card; `409 CHANGE_NOT_ALLOWED` reason `NOTHING_DUE`.

<!-- example: retry-payment-result response RetryPaymentResultEnvelope 202 -->
```json
{
  "description": null,
  "data": {
    "payment": {
      "id": 9005,
      "reference": "sub-l-12-rn-1-2",
      "purpose": "RETRY",
      "status": "PENDING",
      "amountPesewas": 39900,
      "currency": "GHS",
      "attemptNo": 2,
      "planCode": "GROWTH",
      "planName": "Growth",
      "interval": "MONTHLY",
      "periodStart": "2027-01-11T09:30:00Z",
      "periodEnd": "2027-02-11T09:30:00Z",
      "createdAt": "2027-01-12T09:15:00Z",
      "paidAt": null,
      "failureCode": null,
      "refundedPesewas": 0,
      "documents": []
    },
    "summary": {
      "view": "FULL",
      "serverTime": "2027-01-12T09:15:30Z",
      "billingEnabled": true,
      "billingMode": "PAYSTACK",
      "plan": {
        "id": 2,
        "code": "GROWTH",
        "name": "Growth",
        "monthlyPricePesewas": 39900,
        "annualPricePesewas": 383040
      },
      "interval": "MONTHLY",
      "state": {
        "lifecycleStatus": "PAST_DUE",
        "cancelAtPeriodEnd": false,
        "complimentary": false,
        "paymentStatus": "PENDING",
        "accessState": "GRACE"
      },
      "trialEndsAt": null,
      "currentPeriod": {
        "start": "2026-12-11T09:30:00Z",
        "end": "2027-01-11T09:30:00Z"
      },
      "cancelEffectiveAt": null,
      "complimentaryUntil": null,
      "nextBilling": {
        "at": "2027-01-12T09:30:00Z",
        "amountPesewas": 39900,
        "planCode": "GROWTH",
        "interval": "MONTHLY",
        "reason": "RETRY"
      },
      "lockedPricePesewas": 39900,
      "grace": {
        "startedAt": "2027-01-11T09:30:00Z",
        "endsAt": "2027-01-18T09:30:00Z",
        "nextRetryAt": "2027-01-12T09:30:00Z",
        "retriesRemaining": 3,
        "reason": "PAYMENT_FAILED"
      },
      "pendingChange": null,
      "priceChange": null,
      "card": {
        "brand": "visa",
        "last4": "4081",
        "expMonth": 12,
        "expYear": 2029,
        "bank": "Test Bank",
        "expiringSoon": false
      },
      "actions": {
        "canChangePlan": false,
        "canCancel": true,
        "canReactivate": false,
        "canUpdateCard": true,
        "canRetryPayment": false,
        "canSubscribe": false
      },
      "banners": [],
      "version": 11
    }
  }
}
```

<!-- example: payment-pending-error response PaymentPendingEnvelope 409 -->
```json
{
  "description": "A payment is already being processed. Try again in a moment.",
  "data": {
    "code": "PAYMENT_PENDING",
    "paymentId": 9005
  }
}
```

<!-- example: card-required-error response CodeErrorEnvelope 409 -->
```json
{
  "description": "Add a card first.",
  "data": {
    "code": "CARD_REQUIRED"
  }
}
```

<!-- example: billing-not-configured response CodeErrorEnvelope 409 -->
```json
{
  "description": "Billing is not enabled for this platform.",
  "data": {
    "code": "BILLING_NOT_CONFIGURED"
  }
}
```

### 9.6 Plan changes: preview, then apply (FR-10, FR-11, FR-12)

`POST /api/v1/admin/billing/changes/preview` `{planId, interval}` (ADMIN, throttled) changes no state and returns the classification (6.3), the exact charge now, the effective date, the proration breakdown, what changes (entitlements gained and lost, limits against current usage with the "data kept, new creates blocked" warning as `overLimit`), the pending change it would clear or replace, the next billing line, and a signed short-lived `previewToken`. Each response below is one scenario.
Preview errors: `409 CHANGE_NOT_ALLOWED` (`reason`: `NOT_ACTIVE`, `PAYMENT_OVERDUE`, `ENDED`, `COMPLIMENTARY`, `MANUAL_MODE`, `PLAN_NOT_PURCHASABLE` (inactive or not public), `UPGRADE_WITH_INTERVAL_CHANGE`, `TRIAL_ENDED`), `409 PAYMENT_PENDING`, `404` unknown `planId`.

<!-- example: change-preview-request valid ChangePreviewRequest -->
```json
{
  "planId": 3,
  "interval": "MONTHLY"
}
```

<!-- example: change-preview-request-bad-plan invalid ChangePreviewRequest 400 -->
```json
{
  "planId": "scale",
  "interval": "MONTHLY"
}
```

<!-- example: change-preview-upgrade response ChangePreviewEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "classification": "UPGRADE",
    "timing": "IMMEDIATE",
    "effectiveAt": "2026-12-21T09:30:00Z",
    "inTrial": false,
    "from": {
      "planId": 2,
      "planCode": "GROWTH",
      "planName": "Growth",
      "interval": "MONTHLY",
      "pricePesewas": 39900
    },
    "to": {
      "planId": 3,
      "planCode": "SCALE",
      "planName": "Scale",
      "interval": "MONTHLY",
      "pricePesewas": 99900
    },
    "chargeNowPesewas": 40645,
    "proration": {
      "periodStart": "2026-12-11T09:30:00Z",
      "periodEnd": "2027-01-11T09:30:00Z",
      "changeAt": "2026-12-21T09:30:00Z",
      "periodSeconds": 2678400,
      "remainingSeconds": 1814400,
      "priceDifferencePesewas": 60000,
      "chargeNetPesewas": 40645,
      "chargeTaxPesewas": 0,
      "chargeNowPesewas": 40645
    },
    "nextBilling": {
      "at": "2027-01-11T09:30:00Z",
      "amountPesewas": 99900,
      "planCode": "SCALE",
      "interval": "MONTHLY",
      "reason": "RENEWAL"
    },
    "entitlementChanges": {
      "gained": [
        "STOCK_TRANSFERS",
        "STOCK_CONVERSION",
        "ACCOUNTING"
      ],
      "lost": [],
      "limitChanges": [
        {
          "featureKey": "MAX_PRODUCTS",
          "from": 1000,
          "to": null,
          "used": 340,
          "overLimit": false
        },
        {
          "featureKey": "MAX_LOCATIONS",
          "from": 1,
          "to": null,
          "used": 1,
          "overLimit": false
        },
        {
          "featureKey": "MAX_STAFF",
          "from": 3,
          "to": null,
          "used": 2,
          "overLimit": false
        }
      ]
    },
    "overLimitFeatures": [],
    "clearsPendingChange": false,
    "replacesPendingChange": null,
    "previewToken": "pv_A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6",
    "expiresAt": "2026-12-21T09:40:00Z",
    "subscriptionVersion": 6
  }
}
```

<!-- example: change-preview-downgrade response ChangePreviewEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "classification": "DOWNGRADE",
    "timing": "AT_PERIOD_END",
    "effectiveAt": "2027-01-11T09:30:00Z",
    "inTrial": false,
    "from": {
      "planId": 3,
      "planCode": "SCALE",
      "planName": "Scale",
      "interval": "MONTHLY",
      "pricePesewas": 99900
    },
    "to": {
      "planId": 2,
      "planCode": "GROWTH",
      "planName": "Growth",
      "interval": "MONTHLY",
      "pricePesewas": 39900
    },
    "chargeNowPesewas": 0,
    "proration": null,
    "nextBilling": {
      "at": "2027-01-11T09:30:00Z",
      "amountPesewas": 39900,
      "planCode": "GROWTH",
      "interval": "MONTHLY",
      "reason": "RENEWAL"
    },
    "entitlementChanges": {
      "gained": [],
      "lost": [
        "STOCK_TRANSFERS",
        "STOCK_CONVERSION",
        "ACCOUNTING"
      ],
      "limitChanges": [
        {
          "featureKey": "MAX_PRODUCTS",
          "from": null,
          "to": 1000,
          "used": 340,
          "overLimit": false
        },
        {
          "featureKey": "MAX_LOCATIONS",
          "from": null,
          "to": 1,
          "used": 2,
          "overLimit": true
        },
        {
          "featureKey": "MAX_STAFF",
          "from": null,
          "to": 3,
          "used": 2,
          "overLimit": false
        }
      ]
    },
    "overLimitFeatures": [
      "MAX_LOCATIONS"
    ],
    "clearsPendingChange": false,
    "replacesPendingChange": null,
    "previewToken": "pv_A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6",
    "expiresAt": "2026-12-21T09:40:00Z",
    "subscriptionVersion": 7
  }
}
```

<!-- example: change-preview-interval-change response ChangePreviewEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "classification": "INTERVAL_CHANGE",
    "timing": "AT_PERIOD_END",
    "effectiveAt": "2027-12-11T09:30:00Z",
    "inTrial": false,
    "from": {
      "planId": 2,
      "planCode": "GROWTH",
      "planName": "Growth",
      "interval": "ANNUAL",
      "pricePesewas": 383040
    },
    "to": {
      "planId": 2,
      "planCode": "GROWTH",
      "planName": "Growth",
      "interval": "MONTHLY",
      "pricePesewas": 39900
    },
    "chargeNowPesewas": 0,
    "proration": null,
    "nextBilling": {
      "at": "2027-12-11T09:30:00Z",
      "amountPesewas": 39900,
      "planCode": "GROWTH",
      "interval": "MONTHLY",
      "reason": "RENEWAL"
    },
    "entitlementChanges": {
      "gained": [],
      "lost": [],
      "limitChanges": []
    },
    "overLimitFeatures": [],
    "clearsPendingChange": false,
    "replacesPendingChange": null,
    "previewToken": "pv_A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6",
    "expiresAt": "2026-12-21T09:40:00Z",
    "subscriptionVersion": 4
  }
}
```

<!-- example: change-preview-same-clears-pending response ChangePreviewEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "classification": "SAME",
    "timing": "NONE",
    "effectiveAt": "2026-12-21T09:31:30Z",
    "inTrial": false,
    "from": {
      "planId": 3,
      "planCode": "SCALE",
      "planName": "Scale",
      "interval": "MONTHLY",
      "pricePesewas": 99900
    },
    "to": {
      "planId": 3,
      "planCode": "SCALE",
      "planName": "Scale",
      "interval": "MONTHLY",
      "pricePesewas": 99900
    },
    "chargeNowPesewas": 0,
    "proration": null,
    "nextBilling": {
      "at": "2027-01-11T09:30:00Z",
      "amountPesewas": 99900,
      "planCode": "SCALE",
      "interval": "MONTHLY",
      "reason": "RENEWAL"
    },
    "entitlementChanges": {
      "gained": [],
      "lost": [],
      "limitChanges": []
    },
    "overLimitFeatures": [],
    "clearsPendingChange": true,
    "replacesPendingChange": {
      "kind": "DOWNGRADE",
      "planId": 2,
      "planCode": "GROWTH",
      "planName": "Growth",
      "interval": "MONTHLY",
      "effectiveAt": "2027-01-11T09:30:00Z",
      "pricePesewas": 39900
    },
    "previewToken": "pv_A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6",
    "expiresAt": "2026-12-21T09:40:00Z",
    "subscriptionVersion": 7
  }
}
```

<!-- example: change-preview-in-trial response ChangePreviewEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "classification": "UPGRADE",
    "timing": "IMMEDIATE",
    "effectiveAt": "2026-12-05T09:30:00Z",
    "inTrial": true,
    "from": {
      "planId": 2,
      "planCode": "GROWTH",
      "planName": "Growth",
      "interval": "MONTHLY",
      "pricePesewas": 39900
    },
    "to": {
      "planId": 3,
      "planCode": "SCALE",
      "planName": "Scale",
      "interval": "MONTHLY",
      "pricePesewas": 99900
    },
    "chargeNowPesewas": 0,
    "proration": null,
    "nextBilling": {
      "at": "2026-12-11T09:30:00Z",
      "amountPesewas": 99900,
      "planCode": "SCALE",
      "interval": "MONTHLY",
      "reason": "TRIAL_CONVERSION"
    },
    "entitlementChanges": {
      "gained": [
        "STOCK_TRANSFERS",
        "STOCK_CONVERSION",
        "ACCOUNTING"
      ],
      "lost": [],
      "limitChanges": [
        {
          "featureKey": "MAX_PRODUCTS",
          "from": 1000,
          "to": null,
          "used": 340,
          "overLimit": false
        },
        {
          "featureKey": "MAX_LOCATIONS",
          "from": 1,
          "to": null,
          "used": 1,
          "overLimit": false
        },
        {
          "featureKey": "MAX_STAFF",
          "from": 3,
          "to": null,
          "used": 2,
          "overLimit": false
        }
      ]
    },
    "overLimitFeatures": [],
    "clearsPendingChange": false,
    "replacesPendingChange": null,
    "previewToken": "pv_A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6",
    "expiresAt": "2026-12-05T09:40:00Z",
    "subscriptionVersion": 3
  }
}
```

`POST /api/v1/admin/billing/changes` `{previewToken}` + `Idempotency-Key` (required; ADMIN; throttled per tenant). The token (HMAC-signed by the server, opaque to the client) binds tenant, target plan, interval, `chargeNowPesewas`, `changeAt` and the subscription `version`, and expires after 10 minutes.
Outcomes (`ChangeResult.outcome`): `APPLIED` (`200`: an upgrade charged and applied, or an in-trial change), `SCHEDULED` (`200`: downgrade / interval change recorded as the one pending change), `PENDING_CLEARED` (`200`: `SAME` with a pending change), `PAYMENT_PENDING` (`202`: the charge outcome is not known yet; the plan is unchanged; poll `GET /payments/{id}` or `GET /summary` until the payment is terminal). Every result carries a fresh `summary`.

<!-- example: change-request valid ChangeRequest -->
```json
{
  "previewToken": "pv_A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6"
}
```

<!-- example: change-request-missing-token invalid ChangeRequest 400 -->
```json
{}
```

<!-- example: change-result-upgrade-applied response ChangeResultEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "outcome": "APPLIED",
    "classification": "UPGRADE",
    "payment": {
      "id": 9002,
      "reference": "sub-l-12-up-1-1",
      "purpose": "UPGRADE",
      "status": "SUCCESS",
      "amountPesewas": 40645,
      "currency": "GHS",
      "attemptNo": 1,
      "planCode": "SCALE",
      "planName": "Scale",
      "interval": "MONTHLY",
      "periodStart": "2026-12-11T09:30:00Z",
      "periodEnd": "2027-01-11T09:30:00Z",
      "createdAt": "2026-12-21T09:30:00Z",
      "paidAt": "2026-12-21T09:30:05Z",
      "failureCode": null,
      "refundedPesewas": 0,
      "documents": [
        {
          "id": 503,
          "type": "INVOICE",
          "number": "SUB-2026-000002"
        },
        {
          "id": 504,
          "type": "RECEIPT",
          "number": "REC-2026-000002"
        }
      ]
    },
    "summary": {
      "view": "FULL",
      "serverTime": "2026-12-21T09:30:08Z",
      "billingEnabled": true,
      "billingMode": "PAYSTACK",
      "plan": {
        "id": 3,
        "code": "SCALE",
        "name": "Scale",
        "monthlyPricePesewas": 99900,
        "annualPricePesewas": 959040
      },
      "interval": "MONTHLY",
      "state": {
        "lifecycleStatus": "ACTIVE",
        "cancelAtPeriodEnd": false,
        "complimentary": false,
        "paymentStatus": "CURRENT",
        "accessState": "FULL"
      },
      "trialEndsAt": null,
      "currentPeriod": {
        "start": "2026-12-11T09:30:00Z",
        "end": "2027-01-11T09:30:00Z"
      },
      "cancelEffectiveAt": null,
      "complimentaryUntil": null,
      "nextBilling": {
        "at": "2027-01-11T09:30:00Z",
        "amountPesewas": 99900,
        "planCode": "SCALE",
        "interval": "MONTHLY",
        "reason": "RENEWAL"
      },
      "lockedPricePesewas": 99900,
      "grace": null,
      "pendingChange": null,
      "priceChange": null,
      "card": {
        "brand": "visa",
        "last4": "4081",
        "expMonth": 12,
        "expYear": 2029,
        "bank": "Test Bank",
        "expiringSoon": false
      },
      "actions": {
        "canChangePlan": true,
        "canCancel": true,
        "canReactivate": false,
        "canUpdateCard": true,
        "canRetryPayment": false,
        "canSubscribe": false
      },
      "banners": [
        {
          "kind": "INVOICE_AVAILABLE",
          "severity": "INFO",
          "dismissible": true,
          "params": {
            "documentId": 503,
            "documentType": "INVOICE"
          }
        }
      ],
      "version": 7
    }
  }
}
```

<!-- example: change-result-downgrade-scheduled response ChangeResultEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "outcome": "SCHEDULED",
    "classification": "DOWNGRADE",
    "payment": null,
    "summary": {
      "view": "FULL",
      "serverTime": "2026-12-21T09:31:00Z",
      "billingEnabled": true,
      "billingMode": "PAYSTACK",
      "plan": {
        "id": 3,
        "code": "SCALE",
        "name": "Scale",
        "monthlyPricePesewas": 99900,
        "annualPricePesewas": 959040
      },
      "interval": "MONTHLY",
      "state": {
        "lifecycleStatus": "ACTIVE",
        "cancelAtPeriodEnd": false,
        "complimentary": false,
        "paymentStatus": "CURRENT",
        "accessState": "FULL"
      },
      "trialEndsAt": null,
      "currentPeriod": {
        "start": "2026-12-11T09:30:00Z",
        "end": "2027-01-11T09:30:00Z"
      },
      "cancelEffectiveAt": null,
      "complimentaryUntil": null,
      "nextBilling": {
        "at": "2027-01-11T09:30:00Z",
        "amountPesewas": 39900,
        "planCode": "GROWTH",
        "interval": "MONTHLY",
        "reason": "RENEWAL"
      },
      "lockedPricePesewas": 99900,
      "grace": null,
      "pendingChange": {
        "kind": "DOWNGRADE",
        "planId": 2,
        "planCode": "GROWTH",
        "planName": "Growth",
        "interval": "MONTHLY",
        "effectiveAt": "2027-01-11T09:30:00Z",
        "pricePesewas": 39900
      },
      "priceChange": null,
      "card": {
        "brand": "visa",
        "last4": "4081",
        "expMonth": 12,
        "expYear": 2029,
        "bank": "Test Bank",
        "expiringSoon": false
      },
      "actions": {
        "canChangePlan": true,
        "canCancel": true,
        "canReactivate": false,
        "canUpdateCard": true,
        "canRetryPayment": false,
        "canSubscribe": false
      },
      "banners": [],
      "version": 8
    }
  }
}
```

<!-- example: change-result-payment-pending response ChangeResultEnvelope 202 -->
```json
{
  "description": null,
  "data": {
    "outcome": "PAYMENT_PENDING",
    "classification": "UPGRADE",
    "payment": {
      "id": 9002,
      "reference": "sub-l-12-up-1-1",
      "purpose": "UPGRADE",
      "status": "PENDING",
      "amountPesewas": 40645,
      "currency": "GHS",
      "attemptNo": 1,
      "planCode": "SCALE",
      "planName": "Scale",
      "interval": "MONTHLY",
      "periodStart": "2026-12-11T09:30:00Z",
      "periodEnd": "2027-01-11T09:30:00Z",
      "createdAt": "2026-12-21T09:30:00Z",
      "paidAt": null,
      "failureCode": null,
      "refundedPesewas": 0,
      "documents": []
    },
    "summary": {
      "view": "FULL",
      "serverTime": "2026-12-21T09:30:02Z",
      "billingEnabled": true,
      "billingMode": "PAYSTACK",
      "plan": {
        "id": 2,
        "code": "GROWTH",
        "name": "Growth",
        "monthlyPricePesewas": 39900,
        "annualPricePesewas": 383040
      },
      "interval": "MONTHLY",
      "state": {
        "lifecycleStatus": "ACTIVE",
        "cancelAtPeriodEnd": false,
        "complimentary": false,
        "paymentStatus": "PENDING",
        "accessState": "FULL"
      },
      "trialEndsAt": null,
      "currentPeriod": {
        "start": "2026-12-11T09:30:00Z",
        "end": "2027-01-11T09:30:00Z"
      },
      "cancelEffectiveAt": null,
      "complimentaryUntil": null,
      "nextBilling": {
        "at": "2027-01-11T09:30:00Z",
        "amountPesewas": 39900,
        "planCode": "GROWTH",
        "interval": "MONTHLY",
        "reason": "RENEWAL"
      },
      "lockedPricePesewas": 39900,
      "grace": null,
      "pendingChange": null,
      "priceChange": null,
      "card": {
        "brand": "visa",
        "last4": "4081",
        "expMonth": 12,
        "expYear": 2029,
        "bank": "Test Bank",
        "expiringSoon": false
      },
      "actions": {
        "canChangePlan": true,
        "canCancel": true,
        "canReactivate": false,
        "canUpdateCard": true,
        "canRetryPayment": false,
        "canSubscribe": false
      },
      "banners": [],
      "version": 6
    }
  }
}
```

<!-- example: change-result-pending-cleared response ChangeResultEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "outcome": "PENDING_CLEARED",
    "classification": "SAME",
    "payment": null,
    "summary": {
      "view": "FULL",
      "serverTime": "2026-12-21T09:32:00Z",
      "billingEnabled": true,
      "billingMode": "PAYSTACK",
      "plan": {
        "id": 3,
        "code": "SCALE",
        "name": "Scale",
        "monthlyPricePesewas": 99900,
        "annualPricePesewas": 959040
      },
      "interval": "MONTHLY",
      "state": {
        "lifecycleStatus": "ACTIVE",
        "cancelAtPeriodEnd": false,
        "complimentary": false,
        "paymentStatus": "CURRENT",
        "accessState": "FULL"
      },
      "trialEndsAt": null,
      "currentPeriod": {
        "start": "2026-12-11T09:30:00Z",
        "end": "2027-01-11T09:30:00Z"
      },
      "cancelEffectiveAt": null,
      "complimentaryUntil": null,
      "nextBilling": {
        "at": "2027-01-11T09:30:00Z",
        "amountPesewas": 99900,
        "planCode": "SCALE",
        "interval": "MONTHLY",
        "reason": "RENEWAL"
      },
      "lockedPricePesewas": 99900,
      "grace": null,
      "pendingChange": null,
      "priceChange": null,
      "card": {
        "brand": "visa",
        "last4": "4081",
        "expMonth": 12,
        "expYear": 2029,
        "bank": "Test Bank",
        "expiringSoon": false
      },
      "actions": {
        "canChangePlan": true,
        "canCancel": true,
        "canReactivate": false,
        "canUpdateCard": true,
        "canRetryPayment": false,
        "canSubscribe": false
      },
      "banners": [],
      "version": 9
    }
  }
}
```

Errors of `POST /changes` (precedence: 401, 403, 400 body and `Idempotency-Key`, 404, 409 token / version / state, then the charge):

| HTTP | `data.code` | When |
|---|---|---|
| 400 | `PREVIEW_TOKEN_INVALID` | missing, malformed, tampered, expired or another tenant's token |
| 400 | `IDEMPOTENCY_KEY_REQUIRED` / `IDEMPOTENCY_KEY_INVALID` | header missing / malformed |
| 409 | `PRICE_CHANGED` (`reason` `PRICE` / `PLAN`, `newPricePesewas`, `chargeNowPesewas`) | the target plan's price, its `active` / public state changed since the preview: re-preview with the new numbers |
| 409 | `SUBSCRIPTION_VERSION_CONFLICT` | the subscription changed since the preview (another tab, a webhook, an admin action) |
| 409 | `PENDING_CHANGE_CONFLICT` | a concurrent write won the one-pending-change slot |
| 409 | `CHANGE_NOT_ALLOWED`, `PAYMENT_PENDING` | state rules of 7.6 / 7.7 |
| 409 | `PAYMENT_FAILED` (`data.payment`) | the charge was declined: plan unchanged, no grace |
| 409 | `IDEMPOTENCY_KEY_REUSED` | same key, different request |
| 503 | `PROVIDER_UNAVAILABLE` | Paystack unreachable BEFORE any charge was sent (nothing was charged, retry freely). A charge already sent and not answered is `202 PAYMENT_PENDING`, never 503 |

<!-- example: change-price-changed-error response PriceChangedEnvelope 409 -->
```json
{
  "description": "The price changed since you previewed it. Review the new amount.",
  "data": {
    "code": "PRICE_CHANGED",
    "reason": "PRICE",
    "newPricePesewas": 104900,
    "chargeNowPesewas": 44032
  }
}
```

<!-- example: change-token-invalid-error response CodeErrorEnvelope 400 -->
```json
{
  "description": "This preview is invalid or has expired. Preview the change again.",
  "data": {
    "code": "PREVIEW_TOKEN_INVALID"
  }
}
```

<!-- example: change-not-allowed-overdue response ChangeNotAllowedEnvelope 409 -->
```json
{
  "description": "Pay the overdue amount before changing plan.",
  "data": {
    "code": "CHANGE_NOT_ALLOWED",
    "reason": "PAYMENT_OVERDUE"
  }
}
```

<!-- example: change-not-allowed-manual response ChangeNotAllowedEnvelope 409 -->
```json
{
  "description": "Add a card before changing plan.",
  "data": {
    "code": "CHANGE_NOT_ALLOWED",
    "reason": "MANUAL_MODE"
  }
}
```

<!-- example: change-payment-failed response PaymentFailedEnvelope 409 -->
```json
{
  "description": "The payment was declined. The plan was not changed. Update your card and try again.",
  "data": {
    "code": "PAYMENT_FAILED",
    "payment": {
      "id": 9002,
      "reference": "sub-l-12-up-1-1",
      "purpose": "UPGRADE",
      "status": "FAILED",
      "amountPesewas": 40645,
      "currency": "GHS",
      "attemptNo": 1,
      "planCode": "SCALE",
      "planName": "Scale",
      "interval": "MONTHLY",
      "periodStart": "2026-12-11T09:30:00Z",
      "periodEnd": "2027-01-11T09:30:00Z",
      "createdAt": "2026-12-21T09:30:00Z",
      "paidAt": null,
      "failureCode": "DECLINED",
      "refundedPesewas": 0,
      "documents": []
    }
  }
}
```

<!-- example: change-pending-conflict response CodeErrorEnvelope 409 -->
```json
{
  "description": "Another change was made at the same time. Review the subscription and try again.",
  "data": {
    "code": "PENDING_CHANGE_CONFLICT"
  }
}
```

<!-- example: subscription-version-conflict response VersionConflictEnvelope 409 -->
```json
{
  "description": "The subscription changed since you loaded it. Reload and review.",
  "data": {
    "code": "SUBSCRIPTION_VERSION_CONFLICT",
    "expectedVersion": 6,
    "currentVersion": 7
  }
}
```

<!-- example: idempotency-key-required response CodeErrorEnvelope 400 -->
```json
{
  "description": "Idempotency-Key header is required.",
  "data": {
    "code": "IDEMPOTENCY_KEY_REQUIRED"
  }
}
```

<!-- example: idempotency-key-invalid response CodeErrorEnvelope 400 -->
```json
{
  "description": "Idempotency-Key must be 8 to 64 characters of letters, digits and . _ : -",
  "data": {
    "code": "IDEMPOTENCY_KEY_INVALID"
  }
}
```

<!-- example: idempotency-key-reused response CodeErrorEnvelope 409 -->
```json
{
  "description": "This Idempotency-Key was already used with a different request.",
  "data": {
    "code": "IDEMPOTENCY_KEY_REUSED"
  }
}
```

### 9.7 Billing history (FR-17)

`GET /api/v1/admin/billing/payments?page&size` (ADMIN): every charge attempt of the tenant, newest first: date, amount, plan, reference, status, billing period, failure code, refunded amount and the document ids. Failed and pending attempts appear (status shown) but NEVER with a document (BR-04). Verification holds (`CARD_VERIFY`) are not listed to members. `GET /payments/{id}` returns one attempt (404 for another tenant's id).

<!-- example: payments-page response PaymentPageEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "items": [
      {
        "id": 9004,
        "reference": "sub-l-12-rn-1-2",
        "purpose": "RETRY",
        "status": "SUCCESS",
        "amountPesewas": 39900,
        "currency": "GHS",
        "attemptNo": 2,
        "planCode": "GROWTH",
        "planName": "Growth",
        "interval": "MONTHLY",
        "periodStart": "2027-01-11T09:30:00Z",
        "periodEnd": "2027-02-11T09:30:00Z",
        "createdAt": "2027-01-12T09:30:00Z",
        "paidAt": "2027-01-12T09:30:06Z",
        "failureCode": null,
        "refundedPesewas": 0,
        "documents": [
          {
            "id": 505,
            "type": "INVOICE",
            "number": "SUB-2027-000001"
          },
          {
            "id": 506,
            "type": "RECEIPT",
            "number": "REC-2027-000001"
          }
        ]
      },
      {
        "id": 9003,
        "reference": "sub-l-12-rn-1-1",
        "purpose": "RENEWAL",
        "status": "FAILED",
        "amountPesewas": 39900,
        "currency": "GHS",
        "attemptNo": 1,
        "planCode": "GROWTH",
        "planName": "Growth",
        "interval": "MONTHLY",
        "periodStart": "2027-01-11T09:30:00Z",
        "periodEnd": "2027-02-11T09:30:00Z",
        "createdAt": "2027-01-11T09:30:00Z",
        "paidAt": null,
        "failureCode": "INSUFFICIENT_FUNDS",
        "refundedPesewas": 0,
        "documents": []
      },
      {
        "id": 9002,
        "reference": "sub-l-12-up-1-1",
        "purpose": "UPGRADE",
        "status": "SUCCESS",
        "amountPesewas": 40645,
        "currency": "GHS",
        "attemptNo": 1,
        "planCode": "SCALE",
        "planName": "Scale",
        "interval": "MONTHLY",
        "periodStart": "2026-12-11T09:30:00Z",
        "periodEnd": "2027-01-11T09:30:00Z",
        "createdAt": "2026-12-21T09:30:00Z",
        "paidAt": "2026-12-21T09:30:05Z",
        "failureCode": null,
        "refundedPesewas": 0,
        "documents": [
          {
            "id": 503,
            "type": "INVOICE",
            "number": "SUB-2026-000002"
          },
          {
            "id": 504,
            "type": "RECEIPT",
            "number": "REC-2026-000002"
          }
        ]
      },
      {
        "id": 9001,
        "reference": "sub-l-12-tc-0-1",
        "purpose": "TRIAL_CONVERSION",
        "status": "SUCCESS",
        "amountPesewas": 39900,
        "currency": "GHS",
        "attemptNo": 1,
        "planCode": "GROWTH",
        "planName": "Growth",
        "interval": "MONTHLY",
        "periodStart": "2026-12-11T09:30:00Z",
        "periodEnd": "2027-01-11T09:30:00Z",
        "createdAt": "2026-12-11T09:30:00Z",
        "paidAt": "2026-12-11T09:30:07Z",
        "failureCode": null,
        "refundedPesewas": 10000,
        "documents": [
          {
            "id": 501,
            "type": "INVOICE",
            "number": "SUB-2026-000001"
          },
          {
            "id": 502,
            "type": "RECEIPT",
            "number": "REC-2026-000001"
          }
        ]
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 4,
    "totalPages": 1
  }
}
```

<!-- example: payment-detail response PaymentEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "id": 9002,
    "reference": "sub-l-12-up-1-1",
    "purpose": "UPGRADE",
    "status": "SUCCESS",
    "amountPesewas": 40645,
    "currency": "GHS",
    "attemptNo": 1,
    "planCode": "SCALE",
    "planName": "Scale",
    "interval": "MONTHLY",
    "periodStart": "2026-12-11T09:30:00Z",
    "periodEnd": "2027-01-11T09:30:00Z",
    "createdAt": "2026-12-21T09:30:00Z",
    "paidAt": "2026-12-21T09:30:05Z",
    "failureCode": null,
    "refundedPesewas": 0,
    "documents": [
      {
        "id": 503,
        "type": "INVOICE",
        "number": "SUB-2026-000002"
      },
      {
        "id": 504,
        "type": "RECEIPT",
        "number": "REC-2026-000002"
      }
    ]
  }
}
```

### 9.8 Documents (FR-16, FR-18; contract in section 10)

`GET /api/v1/admin/billing/documents?type=&page&size` (`type` = `INVOICE`, `RECEIPT`, `CREDIT_NOTE`, optional), `GET .../documents/{id}` (JSON, `BillingDocument`), `GET .../documents/{id}/pdf` (the PDF, section 10.4), `POST .../documents/{id}/resend` (re-queues the `INVOICE_AVAILABLE` e-mail with the link to the member area; throttled; `202 {queued: true}`). Another tenant's id: `404`.

<!-- example: documents-page response DocumentPageEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "items": [
      {
        "id": 504,
        "type": "RECEIPT",
        "number": "REC-2026-000002",
        "issuedAt": "2026-12-21T09:30:05Z",
        "currency": "GHS",
        "reference": "sub-l-12-up-1-1",
        "paymentId": 9002,
        "adjustmentId": null,
        "relatedDocumentId": null,
        "status": "PAID",
        "periodStart": "2026-12-11T09:30:00Z",
        "periodEnd": "2027-01-11T09:30:00Z",
        "plan": {
          "code": "SCALE",
          "name": "Scale",
          "interval": "MONTHLY"
        },
        "lineItems": [
          {
            "description": "Upgrade from Growth to Scale, prorated 21 Dec 2026 to 11 Jan 2027",
            "quantity": 1,
            "unitAmountPesewas": 40645,
            "amountPesewas": 40645
          }
        ],
        "amounts": {
          "netPesewas": 40645,
          "taxPesewas": 0,
          "totalPesewas": 40645,
          "taxRateBps": null,
          "taxInclusive": null,
          "taxLabel": null
        },
        "business": {},
        "member": {
          "shopName": "Acme Perfumes",
          "ownerEmail": "owner@acme.example"
        }
      },
      {
        "id": 503,
        "type": "INVOICE",
        "number": "SUB-2026-000002",
        "issuedAt": "2026-12-21T09:30:05Z",
        "currency": "GHS",
        "reference": "sub-l-12-up-1-1",
        "paymentId": 9002,
        "adjustmentId": null,
        "relatedDocumentId": null,
        "status": "PAID",
        "periodStart": "2026-12-11T09:30:00Z",
        "periodEnd": "2027-01-11T09:30:00Z",
        "plan": {
          "code": "SCALE",
          "name": "Scale",
          "interval": "MONTHLY"
        },
        "lineItems": [
          {
            "description": "Upgrade from Growth to Scale, prorated 21 Dec 2026 to 11 Jan 2027",
            "quantity": 1,
            "unitAmountPesewas": 40645,
            "amountPesewas": 40645
          }
        ],
        "amounts": {
          "netPesewas": 40645,
          "taxPesewas": 0,
          "totalPesewas": 40645,
          "taxRateBps": null,
          "taxInclusive": null,
          "taxLabel": null
        },
        "business": {},
        "member": {
          "shopName": "Acme Perfumes",
          "ownerEmail": "owner@acme.example"
        }
      },
      {
        "id": 502,
        "type": "RECEIPT",
        "number": "REC-2026-000001",
        "issuedAt": "2026-12-11T09:30:07Z",
        "currency": "GHS",
        "reference": "sub-l-12-tc-0-1",
        "paymentId": 9001,
        "adjustmentId": null,
        "relatedDocumentId": null,
        "status": "PAID",
        "periodStart": "2026-12-11T09:30:00Z",
        "periodEnd": "2027-01-11T09:30:00Z",
        "plan": {
          "code": "GROWTH",
          "name": "Growth",
          "interval": "MONTHLY"
        },
        "lineItems": [
          {
            "description": "Growth plan, monthly, 11 Dec 2026 to 11 Jan 2027",
            "quantity": 1,
            "unitAmountPesewas": 39900,
            "amountPesewas": 39900
          }
        ],
        "amounts": {
          "netPesewas": 39900,
          "taxPesewas": 0,
          "totalPesewas": 39900,
          "taxRateBps": null,
          "taxInclusive": null,
          "taxLabel": null
        },
        "business": {},
        "member": {
          "shopName": "Acme Perfumes",
          "ownerEmail": "owner@acme.example"
        }
      },
      {
        "id": 501,
        "type": "INVOICE",
        "number": "SUB-2026-000001",
        "issuedAt": "2026-12-11T09:30:07Z",
        "currency": "GHS",
        "reference": "sub-l-12-tc-0-1",
        "paymentId": 9001,
        "adjustmentId": null,
        "relatedDocumentId": null,
        "status": "PAID",
        "periodStart": "2026-12-11T09:30:00Z",
        "periodEnd": "2027-01-11T09:30:00Z",
        "plan": {
          "code": "GROWTH",
          "name": "Growth",
          "interval": "MONTHLY"
        },
        "lineItems": [
          {
            "description": "Growth plan, monthly, 11 Dec 2026 to 11 Jan 2027",
            "quantity": 1,
            "unitAmountPesewas": 39900,
            "amountPesewas": 39900
          }
        ],
        "amounts": {
          "netPesewas": 39900,
          "taxPesewas": 0,
          "totalPesewas": 39900,
          "taxRateBps": null,
          "taxInclusive": null,
          "taxLabel": null
        },
        "business": {},
        "member": {
          "shopName": "Acme Perfumes",
          "ownerEmail": "owner@acme.example"
        }
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 4,
    "totalPages": 1
  }
}
```

<!-- example: document-resend-result response ResendResultEnvelope 202 -->
```json
{
  "description": null,
  "data": {
    "queued": true
  }
}
```

## 10. Invoices, receipts and credit notes (FR-16..18, BR-04, BR-08, 4.5; BILL-05)

### 10.1 What is issued (O10)

| Document | Issued when | Amounts |
|---|---|---|
| `INVOICE` | a payment of purpose `TRIAL_CONVERSION`, `RENEWAL`, `UPGRADE`, `RETRY` or `MANUAL` becomes `SUCCESS` (charges are automatic, so the invoice is the bill of a charge that has been paid: `status PAID`) | positive |
| `RECEIPT` | the same moment (a separate document with its own number; the title says "Receipt" and the status "PAID") | positive, equal to the invoice |
| `CREDIT_NOTE` | a refund becomes `COMPLETED` | negative; references the original invoice (`relatedDocumentId`) and the adjustment (`adjustmentId`) |

**Never** issued: for a failed, pending or abandoned payment, for `CARD_VERIFY` (the hold is refunded and is not a sale), for a zero amount, or for an "unpaid invoice" (reminder e-mails state the amount due instead). Exactly one `INVOICE` and one `RECEIPT` per successful payment and one `CREDIT_NOTE` per completed refund (unique per type and source). They are issued in the SAME transaction as the ledger change that caused them.

### 10.2 Numbering

Counters live in `billing_document_counters` per (document type, year); the year is that of the issue instant in UTC and the counter **resets each year**. The number is rendered with the format settings of section 4.1 (`{YYYY}` the year, `{NNNNNN}` the counter zero-padded to 6 digits, more digits when needed). The counter row is locked (`FOR UPDATE`) while the number is allocated inside the issuing transaction, so numbers are **unique and gapless within committed transactions** (a rolled-back transaction gives its number back); a concurrency test issues 50 documents in parallel and expects 50 distinct consecutive numbers. Changing a format later affects only new documents; `number` is unique across all documents.
In the story: `SUB-2026-000001` / `REC-2026-000001` for the first charge, `SUB-2026-000002` / `REC-2026-000002` for the upgrade, `CN-2026-000001` for the refund, and `SUB-2027-000001` for the first invoice of 2027 (the counter reset).

### 10.3 Immutable snapshot, identity, tax

A document is immutable (a trigger refuses `UPDATE` and `DELETE`, S6; a tenant purge never deletes documents) and carries snapshots taken AT ISSUE: the plan (code, name, interval), the period, the line items, the amounts (`netPesewas`, `taxPesewas`, `totalPesewas`, `taxRateBps`, `taxInclusive`, `taxLabel`), the **business identity** (settings `businessName`, `businessAddress`, `businessTin`, `businessEmail`, `businessPhone`, `invoiceFooterText`) and the **member identity** (shop name, owner name, owner e-mail, billing address if any).
Later setting or member edits never alter an issued document. **Empty identity fields are ABSENT** from `business` (not `null`, not an empty string) and are rendered as absent in the PDF: no blank labels, no `TIN:` line when there is no TIN. The tax line (and `taxRateBps`, `taxInclusive`, `taxLabel` non-null) exists only when `taxRateBps` was set at issue; amounts follow 6.6 (`net + tax = total`).

<!-- example: document-invoice response BillingDocumentEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "id": 501,
    "type": "INVOICE",
    "number": "SUB-2026-000001",
    "issuedAt": "2026-12-11T09:30:07Z",
    "currency": "GHS",
    "reference": "sub-l-12-tc-0-1",
    "paymentId": 9001,
    "adjustmentId": null,
    "relatedDocumentId": null,
    "status": "PAID",
    "periodStart": "2026-12-11T09:30:00Z",
    "periodEnd": "2027-01-11T09:30:00Z",
    "plan": {
      "code": "GROWTH",
      "name": "Growth",
      "interval": "MONTHLY"
    },
    "lineItems": [
      {
        "description": "Growth plan, monthly, 11 Dec 2026 to 11 Jan 2027",
        "quantity": 1,
        "unitAmountPesewas": 39900,
        "amountPesewas": 39900
      }
    ],
    "amounts": {
      "netPesewas": 39900,
      "taxPesewas": 0,
      "totalPesewas": 39900,
      "taxRateBps": null,
      "taxInclusive": null,
      "taxLabel": null
    },
    "business": {},
    "member": {
      "shopName": "Acme Perfumes",
      "ownerEmail": "owner@acme.example"
    }
  }
}
```

<!-- example: document-receipt response BillingDocumentEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "id": 502,
    "type": "RECEIPT",
    "number": "REC-2026-000001",
    "issuedAt": "2026-12-11T09:30:07Z",
    "currency": "GHS",
    "reference": "sub-l-12-tc-0-1",
    "paymentId": 9001,
    "adjustmentId": null,
    "relatedDocumentId": null,
    "status": "PAID",
    "periodStart": "2026-12-11T09:30:00Z",
    "periodEnd": "2027-01-11T09:30:00Z",
    "plan": {
      "code": "GROWTH",
      "name": "Growth",
      "interval": "MONTHLY"
    },
    "lineItems": [
      {
        "description": "Growth plan, monthly, 11 Dec 2026 to 11 Jan 2027",
        "quantity": 1,
        "unitAmountPesewas": 39900,
        "amountPesewas": 39900
      }
    ],
    "amounts": {
      "netPesewas": 39900,
      "taxPesewas": 0,
      "totalPesewas": 39900,
      "taxRateBps": null,
      "taxInclusive": null,
      "taxLabel": null
    },
    "business": {},
    "member": {
      "shopName": "Acme Perfumes",
      "ownerEmail": "owner@acme.example"
    }
  }
}
```

A document issued with identity and an illustrative tax rate (15% inclusive; `34696 + 5204 = 39900`, the first row of the tax table in 6.6):

<!-- example: document-invoice-with-tax response BillingDocumentEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "id": 610,
    "type": "INVOICE",
    "number": "SUB-2027-000014",
    "issuedAt": "2027-02-03T08:00:04Z",
    "currency": "GHS",
    "reference": "sub-l-27-rn-2-1",
    "paymentId": 9120,
    "adjustmentId": null,
    "relatedDocumentId": null,
    "status": "PAID",
    "periodStart": "2027-02-03T08:00:00Z",
    "periodEnd": "2027-03-03T08:00:00Z",
    "plan": {
      "code": "GROWTH",
      "name": "Growth",
      "interval": "MONTHLY"
    },
    "lineItems": [
      {
        "description": "Growth plan, monthly, 3 Feb 2027 to 3 Mar 2027",
        "quantity": 1,
        "unitAmountPesewas": 34696,
        "amountPesewas": 34696
      }
    ],
    "amounts": {
      "netPesewas": 34696,
      "taxPesewas": 5204,
      "totalPesewas": 39900,
      "taxRateBps": 1500,
      "taxInclusive": true,
      "taxLabel": "VAT"
    },
    "business": {
      "name": "Example Business Ltd",
      "address": "1 Example Street, Accra",
      "tin": "C0000000000",
      "email": "billing@business.example",
      "phone": "+233200000000",
      "footer": "Thank you for your business."
    },
    "member": {
      "shopName": "Kente Cosmetics",
      "ownerName": "Ama Mensah",
      "ownerEmail": "ama@kente.example",
      "billingAddress": "12 Example Road, Kumasi"
    }
  }
}
```

A credit note for the partial refund of section 7.10 (negative amounts, references the invoice and the adjustment):

<!-- example: document-credit-note response BillingDocumentEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "id": 507,
    "type": "CREDIT_NOTE",
    "number": "CN-2026-000001",
    "issuedAt": "2026-12-30T10:00:05Z",
    "currency": "GHS",
    "reference": "sub-l-12-tc-0-1",
    "paymentId": 9001,
    "adjustmentId": 7001,
    "relatedDocumentId": 501,
    "status": "CREDITED",
    "periodStart": "2026-12-11T09:30:00Z",
    "periodEnd": "2027-01-11T09:30:00Z",
    "plan": {
      "code": "GROWTH",
      "name": "Growth",
      "interval": "MONTHLY"
    },
    "lineItems": [
      {
        "description": "Refund of part of invoice SUB-2026-000001",
        "quantity": 1,
        "unitAmountPesewas": -10000,
        "amountPesewas": -10000
      }
    ],
    "amounts": {
      "netPesewas": -10000,
      "taxPesewas": 0,
      "totalPesewas": -10000,
      "taxRateBps": null,
      "taxInclusive": null,
      "taxLabel": null
    },
    "business": {},
    "member": {
      "shopName": "Acme Perfumes",
      "ownerEmail": "owner@acme.example"
    }
  }
}
```

### 10.4 PDF download

`GET /api/v1/admin/billing/documents/{id}/pdf` (ADMIN, own tenant) and `GET /api/v1/platform/billing/documents/{id}/pdf` (SUPER_ADMIN) render the PDF **on demand from the immutable row**; nothing is stored. Response `200`:

| Header | Value |
|---|---|
| `Content-Type` | `application/pdf` |
| `Content-Disposition` | `attachment; filename="<number>.pdf"` (the number reduced to `A-Za-z0-9._-`) |
| `Cache-Control` | `private, no-store` |
| `X-Content-Type-Options` | `nosniff` |

Rendering rules (S9): programmatic layout from the row only (no HTML-to-PDF renderer, no external fetch, no URL loaded: SSRF), every text escaped, fonts embedded, **deterministic bytes** for the same document (no render timestamp; the PDF creation date is `issuedAt`), `INVOICE`, `RECEIPT` and `CREDIT NOTE` have visibly different titles, the receipt shows "PAID". A test extracts the text library-agnostically and asserts number, total, period and reference.
**The renderer library is decided (O18, owner YES, section 20.2):** OpenPDF or Apache PDFBox, chosen and justified by BILL-05. The endpoint, headers, determinism and content rules above are final.

### 10.5 Delivery

`INVOICE_AVAILABLE` is e-mailed once per document (dedupe key `invoice-available:<documentId>`, section 16) to the tenant owner e-mail with a link to `/admin/settings/billing` on the tenant host; no PDF is attached unless `attachPdfToEmail` is on (O18); the in-app banner `INVOICE_AVAILABLE` shows for 7 days. The member can re-send a document (`POST /documents/{id}/resend`, throttled) and the console can too.

## 11. Platform console API (`/api/v1/platform/billing/**`, FR-22, FR-26, BR-10; BILL-06)

SUPER_ADMIN only on every route (class `@PreAuthorize("hasRole('SUPER_ADMIN')")` plus the matcher, authz matrix test per endpoint: anonymous `401`, every tenant role token `401`, non-SUPER_ADMIN platform admin `403`). Writes accept `application/json` only and stay behind the platform Origin guard ([platform-tenants.md](platform-tenants.md)); money and access actions are throttled per admin (section 18).
Tenants are addressed by `slug` with the same validation as the tenant endpoints (a malformed or unknown slug is the identical `404`); rows carry the immutable tenant `id`. No response contains an authorization code, a secret, a raw webhook body, or a member e-mail other than the owner e-mail the console already shows. Billing audit of every write: section 17.5.

### 11.1 `GET /api/v1/platform/billing/subscriptions`

Paged (`page`, `size` 1..100, default 20), sorted, searchable, filterable. Every filter is an optional query parameter; an unknown enum value, a malformed number or an out-of-range value is `400` with the field map (`{"description": "Validation failed", "data": {"<param>": "<message>"}}`); unknown parameters are ignored.

| Parameter | Values | Meaning |
|---|---|---|
| `search` | text <= 64 | case-insensitive substring of slug, display name or owner e-mail |
| `status` | comma list of `TRIALING,ACTIVE,PAST_DUE,CANCELED,EXPIRED` | lifecycle |
| `paymentStatus` | comma list of `NONE,CURRENT,PENDING,RETRYING,FAILED` | |
| `accessState` | comma list of `FULL,GRACE,READ_ONLY` | |
| `planId`, `interval` | integer, `MONTHLY` / `ANNUAL` | |
| `cancelAtPeriodEnd`, `complimentary` | boolean | |
| `trialing` | boolean | lifecycle `TRIALING` |
| `trialEndsWithinDays` | 1..365 | `TRIALING` and `trialEndsAt <= now + N days` |
| `renewalsDueWithinDays` | 1..365 | `next_billing_at` within N days, lifecycle `TRIALING`, `ACTIVE` or `PAST_DUE`, not complimentary |
| `failedPayments` | boolean | `paymentStatus` is `RETRYING` or `FAILED` |
| `ended` | boolean | lifecycle `CANCELED` or `EXPIRED` |
| `billingMode`, `environment` | `PAYSTACK` / `MANUAL`, `TEST` / `LIVE` | |
| `sort`, `direction` | `nextBillingAt` (default), `trialEndsAt`, `currentPeriodEnd`, `slug`, `updatedAt`; `asc` (default) / `desc` | nulls last |

Quick filters of the console map onto these: All (none), Trialing (`trialing=true`), Active (`status=ACTIVE`), Cancelled/Expired (`ended=true`), Upcoming renewals (`renewalsDueWithinDays=7`), Failed payments (`failedPayments=true`), Complimentary (`complimentary=true`). `facets` returns the counts of these quick filters over the whole environment-filtered set, not only the page. Rows carry the public-safe fields only (no authorization data).

<!-- example: subscriptions-page response SubscriptionPageEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "items": [
      {
        "tenant": {
          "id": 12,
          "slug": "acme",
          "displayName": "Acme Perfumes",
          "ownerEmail": "owner@acme.example"
        },
        "plan": {
          "code": "GROWTH",
          "name": "Growth"
        },
        "interval": "MONTHLY",
        "state": {
          "lifecycleStatus": "PAST_DUE",
          "cancelAtPeriodEnd": false,
          "complimentary": false,
          "paymentStatus": "RETRYING",
          "accessState": "GRACE"
        },
        "billingMode": "PAYSTACK",
        "environment": "LIVE",
        "trialEndsAt": null,
        "currentPeriodEnd": "2027-01-11T09:30:00Z",
        "nextBillingAt": "2027-01-12T09:30:00Z",
        "nextBillingAmountPesewas": 39900,
        "cardOnFile": true,
        "lastPayment": {
          "status": "FAILED",
          "at": "2027-01-11T09:30:00Z",
          "amountPesewas": 39900
        },
        "version": 9
      },
      {
        "tenant": {
          "id": 27,
          "slug": "kente",
          "displayName": "Kente Cosmetics",
          "ownerEmail": "ama@kente.example"
        },
        "plan": {
          "code": "GROWTH",
          "name": "Growth"
        },
        "interval": "ANNUAL",
        "state": {
          "lifecycleStatus": "ACTIVE",
          "cancelAtPeriodEnd": false,
          "complimentary": false,
          "paymentStatus": "CURRENT",
          "accessState": "FULL"
        },
        "billingMode": "PAYSTACK",
        "environment": "LIVE",
        "trialEndsAt": null,
        "currentPeriodEnd": "2027-12-03T08:00:00Z",
        "nextBillingAt": "2027-12-03T08:00:00Z",
        "nextBillingAmountPesewas": 383040,
        "cardOnFile": true,
        "lastPayment": {
          "status": "SUCCESS",
          "at": "2026-12-03T08:00:05Z",
          "amountPesewas": 383040
        },
        "version": 14
      },
      {
        "tenant": {
          "id": 31,
          "slug": "zuri",
          "displayName": "Zuri Threads",
          "ownerEmail": "zuri@zuri.example"
        },
        "plan": {
          "code": "STARTER",
          "name": "Starter"
        },
        "interval": "MONTHLY",
        "state": {
          "lifecycleStatus": "TRIALING",
          "cancelAtPeriodEnd": false,
          "complimentary": false,
          "paymentStatus": "CURRENT",
          "accessState": "FULL"
        },
        "billingMode": "PAYSTACK",
        "environment": "LIVE",
        "trialEndsAt": "2027-01-20T10:00:00Z",
        "currentPeriodEnd": null,
        "nextBillingAt": "2027-01-20T10:00:00Z",
        "nextBillingAmountPesewas": 14900,
        "cardOnFile": true,
        "lastPayment": null,
        "version": 2
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 3,
    "totalPages": 1,
    "sort": "nextBillingAt",
    "direction": "asc",
    "facets": {
      "TRIALING": 1,
      "ACTIVE": 1,
      "PAST_DUE": 1,
      "CANCELED": 0,
      "EXPIRED": 0,
      "COMPLIMENTARY": 0,
      "RENEWALS_DUE_7D": 1,
      "FAILED_PAYMENTS": 1
    }
  }
}
```

### 11.2 `GET /api/v1/platform/billing/subscriptions/{slug}`

The detail: the three state fields, `manualRestriction`, the schedule (`currentPeriod`, `anchorAt`, `nextBillingAt`, `grace.retrySchedule`), the pending change, the price lock (`lockedPricesPesewas`), the price change notice, the complimentary info, card display data, the `timeline` of transitions (newest first, each with `from` / `to` state, event code, actor type and reference), `payments` (admin view with environment, fees, verification, flags and the refund arithmetic), `documents`, `adjustments` and the `recentAudit` rows. `version` is the lock input of every write below. A tenant without a subscription row (a `MANUAL` tenant never converted) answers with `billingMode MANUAL` and the legacy projection (`lifecycleStatus` from `subscription_status`, empty lists).

<!-- example: subscription-detail response SubscriptionDetailEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "tenant": {
      "id": 12,
      "slug": "acme",
      "displayName": "Acme Perfumes",
      "ownerEmail": "owner@acme.example"
    },
    "plan": {
      "id": 2,
      "code": "GROWTH",
      "name": "Growth",
      "monthlyPricePesewas": 39900,
      "annualPricePesewas": 383040
    },
    "interval": "MONTHLY",
    "state": {
      "lifecycleStatus": "PAST_DUE",
      "cancelAtPeriodEnd": false,
      "complimentary": false,
      "paymentStatus": "RETRYING",
      "accessState": "GRACE"
    },
    "billingMode": "PAYSTACK",
    "environment": "LIVE",
    "manualRestriction": false,
    "trialEndsAt": "2026-12-11T09:30:00Z",
    "currentPeriod": {
      "start": "2026-12-11T09:30:00Z",
      "end": "2027-01-11T09:30:00Z"
    },
    "anchorAt": "2026-12-11T09:30:00Z",
    "nextBillingAt": "2027-01-12T09:30:00Z",
    "lockedPricesPesewas": {
      "MONTHLY": 39900,
      "ANNUAL": 383040
    },
    "grace": {
      "startedAt": "2027-01-11T09:30:00Z",
      "endsAt": "2027-01-18T09:30:00Z",
      "reason": "PAYMENT_FAILED",
      "retrySchedule": [
        {
          "attemptNo": 2,
          "scheduledFor": "2027-01-12T09:30:00Z",
          "status": "SCHEDULED"
        },
        {
          "attemptNo": 3,
          "scheduledFor": "2027-01-14T09:30:00Z",
          "status": "SCHEDULED"
        },
        {
          "attemptNo": 4,
          "scheduledFor": "2027-01-16T09:30:00Z",
          "status": "SCHEDULED"
        }
      ]
    },
    "pendingChange": null,
    "priceChange": null,
    "complimentary": null,
    "card": {
      "brand": "visa",
      "last4": "4081",
      "expMonth": 12,
      "expYear": 2029,
      "bank": "Test Bank",
      "expiringSoon": false
    },
    "version": 9,
    "timeline": [
      {
        "at": "2027-01-11T09:30:04Z",
        "event": "CHARGE_FAILED",
        "from": {
          "lifecycleStatus": "ACTIVE",
          "cancelAtPeriodEnd": false,
          "complimentary": false,
          "paymentStatus": "CURRENT",
          "accessState": "FULL"
        },
        "to": {
          "lifecycleStatus": "PAST_DUE",
          "cancelAtPeriodEnd": false,
          "complimentary": false,
          "paymentStatus": "RETRYING",
          "accessState": "GRACE"
        },
        "actorType": "SYSTEM",
        "reference": "sub-l-12-rn-1-1"
      },
      {
        "at": "2026-12-21T09:30:05Z",
        "event": "UPGRADE_APPLIED",
        "from": {
          "lifecycleStatus": "ACTIVE",
          "cancelAtPeriodEnd": false,
          "complimentary": false,
          "paymentStatus": "CURRENT",
          "accessState": "FULL"
        },
        "to": {
          "lifecycleStatus": "ACTIVE",
          "cancelAtPeriodEnd": false,
          "complimentary": false,
          "paymentStatus": "CURRENT",
          "accessState": "FULL"
        },
        "actorType": "MEMBER",
        "reference": "sub-l-12-up-1-1"
      },
      {
        "at": "2026-12-11T09:30:07Z",
        "event": "TRIAL_CHARGE_SUCCEEDED",
        "from": {
          "lifecycleStatus": "TRIALING",
          "cancelAtPeriodEnd": false,
          "complimentary": false,
          "paymentStatus": "CURRENT",
          "accessState": "FULL"
        },
        "to": {
          "lifecycleStatus": "ACTIVE",
          "cancelAtPeriodEnd": false,
          "complimentary": false,
          "paymentStatus": "CURRENT",
          "accessState": "FULL"
        },
        "actorType": "SYSTEM",
        "reference": "sub-l-12-tc-0-1"
      },
      {
        "at": "2026-11-27T09:30:00Z",
        "event": "TRIAL_STARTED",
        "from": null,
        "to": {
          "lifecycleStatus": "TRIALING",
          "cancelAtPeriodEnd": false,
          "complimentary": false,
          "paymentStatus": "CURRENT",
          "accessState": "FULL"
        },
        "actorType": "SYSTEM",
        "reference": null
      }
    ],
    "payments": [
      {
        "id": 9003,
        "reference": "sub-l-12-rn-1-1",
        "purpose": "RENEWAL",
        "status": "FAILED",
        "amountPesewas": 39900,
        "currency": "GHS",
        "attemptNo": 1,
        "environment": "LIVE",
        "planCode": "GROWTH",
        "interval": "MONTHLY",
        "periodStart": "2027-01-11T09:30:00Z",
        "periodEnd": "2027-02-11T09:30:00Z",
        "createdAt": "2027-01-11T09:30:00Z",
        "paidAt": null,
        "verifiedAt": null,
        "feesPesewas": null,
        "failureCode": "INSUFFICIENT_FUNDS",
        "flagged": false,
        "flagReason": null,
        "refundedPesewas": 0,
        "pendingRefundPesewas": 0,
        "refundableRemainingPesewas": 0,
        "refundableUntil": null,
        "documents": []
      },
      {
        "id": 9002,
        "reference": "sub-l-12-up-1-1",
        "purpose": "UPGRADE",
        "status": "SUCCESS",
        "amountPesewas": 40645,
        "currency": "GHS",
        "attemptNo": 1,
        "environment": "LIVE",
        "planCode": "SCALE",
        "interval": "MONTHLY",
        "periodStart": "2026-12-11T09:30:00Z",
        "periodEnd": "2027-01-11T09:30:00Z",
        "createdAt": "2026-12-21T09:30:00Z",
        "paidAt": "2026-12-21T09:30:05Z",
        "verifiedAt": "2026-12-21T09:30:05Z",
        "feesPesewas": null,
        "failureCode": null,
        "flagged": false,
        "flagReason": null,
        "refundedPesewas": 0,
        "pendingRefundPesewas": 0,
        "refundableRemainingPesewas": 40645,
        "refundableUntil": "2027-03-21T09:30:05Z",
        "documents": [
          {
            "id": 503,
            "type": "INVOICE",
            "number": "SUB-2026-000002"
          },
          {
            "id": 504,
            "type": "RECEIPT",
            "number": "REC-2026-000002"
          }
        ]
      },
      {
        "id": 9001,
        "reference": "sub-l-12-tc-0-1",
        "purpose": "TRIAL_CONVERSION",
        "status": "SUCCESS",
        "amountPesewas": 39900,
        "currency": "GHS",
        "attemptNo": 1,
        "environment": "LIVE",
        "planCode": "GROWTH",
        "interval": "MONTHLY",
        "periodStart": "2026-12-11T09:30:00Z",
        "periodEnd": "2027-01-11T09:30:00Z",
        "createdAt": "2026-12-11T09:30:00Z",
        "paidAt": "2026-12-11T09:30:07Z",
        "verifiedAt": "2026-12-11T09:30:07Z",
        "feesPesewas": null,
        "failureCode": null,
        "flagged": false,
        "flagReason": null,
        "refundedPesewas": 10000,
        "pendingRefundPesewas": 0,
        "refundableRemainingPesewas": 29900,
        "refundableUntil": "2027-03-11T09:30:07Z",
        "documents": [
          {
            "id": 501,
            "type": "INVOICE",
            "number": "SUB-2026-000001"
          },
          {
            "id": 502,
            "type": "RECEIPT",
            "number": "REC-2026-000001"
          }
        ]
      }
    ],
    "documents": [
      {
        "id": 504,
        "type": "RECEIPT",
        "number": "REC-2026-000002"
      },
      {
        "id": 503,
        "type": "INVOICE",
        "number": "SUB-2026-000002"
      },
      {
        "id": 502,
        "type": "RECEIPT",
        "number": "REC-2026-000001"
      },
      {
        "id": 501,
        "type": "INVOICE",
        "number": "SUB-2026-000001"
      },
      {
        "id": 507,
        "type": "CREDIT_NOTE",
        "number": "CN-2026-000001"
      }
    ],
    "adjustments": [
      {
        "id": 7001,
        "type": "REFUND",
        "amountPesewas": -10000,
        "paymentId": 9001,
        "reason": "Customer overcharged during onboarding",
        "actorEmail": "admin@shopfi.test",
        "createdAt": "2026-12-30T10:00:05Z"
      }
    ],
    "recentAudit": [
      {
        "id": 9112,
        "occurredAt": "2027-01-11T09:30:04Z",
        "actorType": "SYSTEM",
        "actorId": null,
        "actorEmail": null,
        "tenantId": 12,
        "tenantSlug": "acme",
        "action": "PAYMENT_FAILED",
        "reference": "sub-l-12-rn-1-1",
        "reason": null,
        "before": {
          "lifecycleStatus": "ACTIVE",
          "paymentStatus": "CURRENT",
          "accessState": "FULL"
        },
        "after": {
          "lifecycleStatus": "PAST_DUE",
          "paymentStatus": "RETRYING",
          "accessState": "GRACE"
        }
      },
      {
        "id": 9087,
        "occurredAt": "2026-12-30T10:00:05Z",
        "actorType": "PLATFORM_ADMIN",
        "actorId": 1,
        "actorEmail": "admin@shopfi.test",
        "tenantId": 12,
        "tenantSlug": "acme",
        "action": "REFUND_COMPLETED",
        "reference": "sub-l-12-tc-0-1",
        "reason": "Customer overcharged during onboarding",
        "before": {
          "refundedPesewas": 0
        },
        "after": {
          "refundedPesewas": 10000
        }
      }
    ]
  }
}
```

### 11.3 Manual actions

`POST .../subscriptions/{slug}/actions`: the body shapes and rules are in 7.11 (`SubscriptionActionRequest`, one shape per action). Results: `200` `SubscriptionDetail`; errors `400` (field map: missing or short reason, unknown action, bad value), `404`, `409 SUBSCRIPTION_VERSION_CONFLICT`, `409 ACTION_NOT_ALLOWED`, `409 BILLING_NOT_CONFIGURED` for actions that need Paystack when billing is off, `429`, `503 PROVIDER_UNAVAILABLE` for `RETRY_PAYMENT_NOW` / `CHANGE_PLAN` with `charge`.

### 11.4 Complimentary plans

`POST .../subscriptions/{slug}/complimentary` and `DELETE .../subscriptions/{slug}/complimentary` (the DELETE carries a JSON body `{reason, expectedVersion}`, an exception to the usual bodiless DELETE because a reason is mandatory and must not travel in a URL): semantics in 7.9. Both return `SubscriptionDetail`.

<!-- example: complimentary-request valid ComplimentaryGrantRequest -->
```json
{
  "planId": 3,
  "until": "2027-06-30T23:59:59Z",
  "reason": "Launch partner, six months free",
  "expectedVersion": 9
}
```

<!-- example: complimentary-request-open-ended valid ComplimentaryGrantRequest -->
```json
{
  "planId": 3,
  "until": null,
  "reason": "Strategic partner, no end date",
  "expectedVersion": 9
}
```

<!-- example: complimentary-request-missing-until invalid ComplimentaryGrantRequest 400 -->
```json
{
  "planId": 3,
  "reason": "Launch partner, six months free",
  "expectedVersion": 9
}
```

<!-- example: complimentary-result response SubscriptionDetailEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "tenant": {
      "id": 12,
      "slug": "acme",
      "displayName": "Acme Perfumes",
      "ownerEmail": "owner@acme.example"
    },
    "plan": {
      "id": 3,
      "code": "SCALE",
      "name": "Scale",
      "monthlyPricePesewas": 99900,
      "annualPricePesewas": 959040
    },
    "interval": "MONTHLY",
    "state": {
      "lifecycleStatus": "ACTIVE",
      "cancelAtPeriodEnd": false,
      "complimentary": true,
      "paymentStatus": "NONE",
      "accessState": "FULL"
    },
    "billingMode": "PAYSTACK",
    "environment": "LIVE",
    "manualRestriction": false,
    "trialEndsAt": "2026-12-11T09:30:00Z",
    "currentPeriod": null,
    "anchorAt": "2026-12-11T09:30:00Z",
    "nextBillingAt": null,
    "lockedPricesPesewas": {
      "MONTHLY": 99900,
      "ANNUAL": 959040
    },
    "grace": null,
    "pendingChange": null,
    "priceChange": null,
    "complimentary": {
      "planCode": "SCALE",
      "since": "2027-01-11T10:00:00Z",
      "until": "2027-06-30T23:59:59Z",
      "reason": "Launch partner, six months free",
      "grantedBy": "admin@shopfi.test"
    },
    "card": {
      "brand": "visa",
      "last4": "4081",
      "expMonth": 12,
      "expYear": 2029,
      "bank": "Test Bank",
      "expiringSoon": false
    },
    "version": 10,
    "timeline": [
      {
        "at": "2027-01-11T09:30:04Z",
        "event": "CHARGE_FAILED",
        "from": {
          "lifecycleStatus": "ACTIVE",
          "cancelAtPeriodEnd": false,
          "complimentary": false,
          "paymentStatus": "CURRENT",
          "accessState": "FULL"
        },
        "to": {
          "lifecycleStatus": "PAST_DUE",
          "cancelAtPeriodEnd": false,
          "complimentary": false,
          "paymentStatus": "RETRYING",
          "accessState": "GRACE"
        },
        "actorType": "SYSTEM",
        "reference": "sub-l-12-rn-1-1"
      },
      {
        "at": "2026-12-21T09:30:05Z",
        "event": "UPGRADE_APPLIED",
        "from": {
          "lifecycleStatus": "ACTIVE",
          "cancelAtPeriodEnd": false,
          "complimentary": false,
          "paymentStatus": "CURRENT",
          "accessState": "FULL"
        },
        "to": {
          "lifecycleStatus": "ACTIVE",
          "cancelAtPeriodEnd": false,
          "complimentary": false,
          "paymentStatus": "CURRENT",
          "accessState": "FULL"
        },
        "actorType": "MEMBER",
        "reference": "sub-l-12-up-1-1"
      },
      {
        "at": "2026-12-11T09:30:07Z",
        "event": "TRIAL_CHARGE_SUCCEEDED",
        "from": {
          "lifecycleStatus": "TRIALING",
          "cancelAtPeriodEnd": false,
          "complimentary": false,
          "paymentStatus": "CURRENT",
          "accessState": "FULL"
        },
        "to": {
          "lifecycleStatus": "ACTIVE",
          "cancelAtPeriodEnd": false,
          "complimentary": false,
          "paymentStatus": "CURRENT",
          "accessState": "FULL"
        },
        "actorType": "SYSTEM",
        "reference": "sub-l-12-tc-0-1"
      },
      {
        "at": "2026-11-27T09:30:00Z",
        "event": "TRIAL_STARTED",
        "from": null,
        "to": {
          "lifecycleStatus": "TRIALING",
          "cancelAtPeriodEnd": false,
          "complimentary": false,
          "paymentStatus": "CURRENT",
          "accessState": "FULL"
        },
        "actorType": "SYSTEM",
        "reference": null
      }
    ],
    "payments": [
      {
        "id": 9003,
        "reference": "sub-l-12-rn-1-1",
        "purpose": "RENEWAL",
        "status": "FAILED",
        "amountPesewas": 39900,
        "currency": "GHS",
        "attemptNo": 1,
        "environment": "LIVE",
        "planCode": "GROWTH",
        "interval": "MONTHLY",
        "periodStart": "2027-01-11T09:30:00Z",
        "periodEnd": "2027-02-11T09:30:00Z",
        "createdAt": "2027-01-11T09:30:00Z",
        "paidAt": null,
        "verifiedAt": null,
        "feesPesewas": null,
        "failureCode": "INSUFFICIENT_FUNDS",
        "flagged": false,
        "flagReason": null,
        "refundedPesewas": 0,
        "pendingRefundPesewas": 0,
        "refundableRemainingPesewas": 0,
        "refundableUntil": null,
        "documents": []
      },
      {
        "id": 9002,
        "reference": "sub-l-12-up-1-1",
        "purpose": "UPGRADE",
        "status": "SUCCESS",
        "amountPesewas": 40645,
        "currency": "GHS",
        "attemptNo": 1,
        "environment": "LIVE",
        "planCode": "SCALE",
        "interval": "MONTHLY",
        "periodStart": "2026-12-11T09:30:00Z",
        "periodEnd": "2027-01-11T09:30:00Z",
        "createdAt": "2026-12-21T09:30:00Z",
        "paidAt": "2026-12-21T09:30:05Z",
        "verifiedAt": "2026-12-21T09:30:05Z",
        "feesPesewas": null,
        "failureCode": null,
        "flagged": false,
        "flagReason": null,
        "refundedPesewas": 0,
        "pendingRefundPesewas": 0,
        "refundableRemainingPesewas": 40645,
        "refundableUntil": "2027-03-21T09:30:05Z",
        "documents": [
          {
            "id": 503,
            "type": "INVOICE",
            "number": "SUB-2026-000002"
          },
          {
            "id": 504,
            "type": "RECEIPT",
            "number": "REC-2026-000002"
          }
        ]
      },
      {
        "id": 9001,
        "reference": "sub-l-12-tc-0-1",
        "purpose": "TRIAL_CONVERSION",
        "status": "SUCCESS",
        "amountPesewas": 39900,
        "currency": "GHS",
        "attemptNo": 1,
        "environment": "LIVE",
        "planCode": "GROWTH",
        "interval": "MONTHLY",
        "periodStart": "2026-12-11T09:30:00Z",
        "periodEnd": "2027-01-11T09:30:00Z",
        "createdAt": "2026-12-11T09:30:00Z",
        "paidAt": "2026-12-11T09:30:07Z",
        "verifiedAt": "2026-12-11T09:30:07Z",
        "feesPesewas": null,
        "failureCode": null,
        "flagged": false,
        "flagReason": null,
        "refundedPesewas": 10000,
        "pendingRefundPesewas": 0,
        "refundableRemainingPesewas": 29900,
        "refundableUntil": "2027-03-11T09:30:07Z",
        "documents": [
          {
            "id": 501,
            "type": "INVOICE",
            "number": "SUB-2026-000001"
          },
          {
            "id": 502,
            "type": "RECEIPT",
            "number": "REC-2026-000001"
          }
        ]
      }
    ],
    "documents": [
      {
        "id": 504,
        "type": "RECEIPT",
        "number": "REC-2026-000002"
      },
      {
        "id": 503,
        "type": "INVOICE",
        "number": "SUB-2026-000002"
      },
      {
        "id": 502,
        "type": "RECEIPT",
        "number": "REC-2026-000001"
      },
      {
        "id": 501,
        "type": "INVOICE",
        "number": "SUB-2026-000001"
      },
      {
        "id": 507,
        "type": "CREDIT_NOTE",
        "number": "CN-2026-000001"
      }
    ],
    "adjustments": [
      {
        "id": 7001,
        "type": "REFUND",
        "amountPesewas": -10000,
        "paymentId": 9001,
        "reason": "Customer overcharged during onboarding",
        "actorEmail": "admin@shopfi.test",
        "createdAt": "2026-12-30T10:00:05Z"
      }
    ],
    "recentAudit": [
      {
        "id": 9112,
        "occurredAt": "2027-01-11T09:30:04Z",
        "actorType": "SYSTEM",
        "actorId": null,
        "actorEmail": null,
        "tenantId": 12,
        "tenantSlug": "acme",
        "action": "PAYMENT_FAILED",
        "reference": "sub-l-12-rn-1-1",
        "reason": null,
        "before": {
          "lifecycleStatus": "ACTIVE",
          "paymentStatus": "CURRENT",
          "accessState": "FULL"
        },
        "after": {
          "lifecycleStatus": "PAST_DUE",
          "paymentStatus": "RETRYING",
          "accessState": "GRACE"
        }
      },
      {
        "id": 9087,
        "occurredAt": "2026-12-30T10:00:05Z",
        "actorType": "PLATFORM_ADMIN",
        "actorId": 1,
        "actorEmail": "admin@shopfi.test",
        "tenantId": 12,
        "tenantSlug": "acme",
        "action": "REFUND_COMPLETED",
        "reference": "sub-l-12-tc-0-1",
        "reason": "Customer overcharged during onboarding",
        "before": {
          "refundedPesewas": 0
        },
        "after": {
          "refundedPesewas": 10000
        }
      }
    ]
  }
}
```

<!-- example: complimentary-end-request valid ComplimentaryEndRequest -->
```json
{
  "reason": "Partnership ended early",
  "expectedVersion": 10
}
```

<!-- example: complimentary-end-result response SubscriptionDetailEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "tenant": {
      "id": 12,
      "slug": "acme",
      "displayName": "Acme Perfumes",
      "ownerEmail": "owner@acme.example"
    },
    "plan": {
      "id": 2,
      "code": "GROWTH",
      "name": "Growth",
      "monthlyPricePesewas": 39900,
      "annualPricePesewas": 383040
    },
    "interval": "MONTHLY",
    "state": {
      "lifecycleStatus": "PAST_DUE",
      "cancelAtPeriodEnd": false,
      "complimentary": false,
      "paymentStatus": "RETRYING",
      "accessState": "GRACE"
    },
    "billingMode": "PAYSTACK",
    "environment": "LIVE",
    "manualRestriction": false,
    "trialEndsAt": "2026-12-11T09:30:00Z",
    "currentPeriod": {
      "start": "2026-12-11T09:30:00Z",
      "end": "2027-01-11T09:30:00Z"
    },
    "anchorAt": "2026-12-11T09:30:00Z",
    "nextBillingAt": "2027-01-12T09:30:00Z",
    "lockedPricesPesewas": {
      "MONTHLY": 39900,
      "ANNUAL": 383040
    },
    "grace": {
      "startedAt": "2027-01-11T09:30:00Z",
      "endsAt": "2027-01-18T09:30:00Z",
      "reason": "PAYMENT_FAILED",
      "retrySchedule": [
        {
          "attemptNo": 2,
          "scheduledFor": "2027-01-12T09:30:00Z",
          "status": "SCHEDULED"
        },
        {
          "attemptNo": 3,
          "scheduledFor": "2027-01-14T09:30:00Z",
          "status": "SCHEDULED"
        },
        {
          "attemptNo": 4,
          "scheduledFor": "2027-01-16T09:30:00Z",
          "status": "SCHEDULED"
        }
      ]
    },
    "pendingChange": null,
    "priceChange": null,
    "complimentary": null,
    "card": {
      "brand": "visa",
      "last4": "4081",
      "expMonth": 12,
      "expYear": 2029,
      "bank": "Test Bank",
      "expiringSoon": false
    },
    "version": 11,
    "timeline": [
      {
        "at": "2027-01-11T09:30:04Z",
        "event": "CHARGE_FAILED",
        "from": {
          "lifecycleStatus": "ACTIVE",
          "cancelAtPeriodEnd": false,
          "complimentary": false,
          "paymentStatus": "CURRENT",
          "accessState": "FULL"
        },
        "to": {
          "lifecycleStatus": "PAST_DUE",
          "cancelAtPeriodEnd": false,
          "complimentary": false,
          "paymentStatus": "RETRYING",
          "accessState": "GRACE"
        },
        "actorType": "SYSTEM",
        "reference": "sub-l-12-rn-1-1"
      },
      {
        "at": "2026-12-21T09:30:05Z",
        "event": "UPGRADE_APPLIED",
        "from": {
          "lifecycleStatus": "ACTIVE",
          "cancelAtPeriodEnd": false,
          "complimentary": false,
          "paymentStatus": "CURRENT",
          "accessState": "FULL"
        },
        "to": {
          "lifecycleStatus": "ACTIVE",
          "cancelAtPeriodEnd": false,
          "complimentary": false,
          "paymentStatus": "CURRENT",
          "accessState": "FULL"
        },
        "actorType": "MEMBER",
        "reference": "sub-l-12-up-1-1"
      },
      {
        "at": "2026-12-11T09:30:07Z",
        "event": "TRIAL_CHARGE_SUCCEEDED",
        "from": {
          "lifecycleStatus": "TRIALING",
          "cancelAtPeriodEnd": false,
          "complimentary": false,
          "paymentStatus": "CURRENT",
          "accessState": "FULL"
        },
        "to": {
          "lifecycleStatus": "ACTIVE",
          "cancelAtPeriodEnd": false,
          "complimentary": false,
          "paymentStatus": "CURRENT",
          "accessState": "FULL"
        },
        "actorType": "SYSTEM",
        "reference": "sub-l-12-tc-0-1"
      },
      {
        "at": "2026-11-27T09:30:00Z",
        "event": "TRIAL_STARTED",
        "from": null,
        "to": {
          "lifecycleStatus": "TRIALING",
          "cancelAtPeriodEnd": false,
          "complimentary": false,
          "paymentStatus": "CURRENT",
          "accessState": "FULL"
        },
        "actorType": "SYSTEM",
        "reference": null
      }
    ],
    "payments": [
      {
        "id": 9003,
        "reference": "sub-l-12-rn-1-1",
        "purpose": "RENEWAL",
        "status": "FAILED",
        "amountPesewas": 39900,
        "currency": "GHS",
        "attemptNo": 1,
        "environment": "LIVE",
        "planCode": "GROWTH",
        "interval": "MONTHLY",
        "periodStart": "2027-01-11T09:30:00Z",
        "periodEnd": "2027-02-11T09:30:00Z",
        "createdAt": "2027-01-11T09:30:00Z",
        "paidAt": null,
        "verifiedAt": null,
        "feesPesewas": null,
        "failureCode": "INSUFFICIENT_FUNDS",
        "flagged": false,
        "flagReason": null,
        "refundedPesewas": 0,
        "pendingRefundPesewas": 0,
        "refundableRemainingPesewas": 0,
        "refundableUntil": null,
        "documents": []
      },
      {
        "id": 9002,
        "reference": "sub-l-12-up-1-1",
        "purpose": "UPGRADE",
        "status": "SUCCESS",
        "amountPesewas": 40645,
        "currency": "GHS",
        "attemptNo": 1,
        "environment": "LIVE",
        "planCode": "SCALE",
        "interval": "MONTHLY",
        "periodStart": "2026-12-11T09:30:00Z",
        "periodEnd": "2027-01-11T09:30:00Z",
        "createdAt": "2026-12-21T09:30:00Z",
        "paidAt": "2026-12-21T09:30:05Z",
        "verifiedAt": "2026-12-21T09:30:05Z",
        "feesPesewas": null,
        "failureCode": null,
        "flagged": false,
        "flagReason": null,
        "refundedPesewas": 0,
        "pendingRefundPesewas": 0,
        "refundableRemainingPesewas": 40645,
        "refundableUntil": "2027-03-21T09:30:05Z",
        "documents": [
          {
            "id": 503,
            "type": "INVOICE",
            "number": "SUB-2026-000002"
          },
          {
            "id": 504,
            "type": "RECEIPT",
            "number": "REC-2026-000002"
          }
        ]
      },
      {
        "id": 9001,
        "reference": "sub-l-12-tc-0-1",
        "purpose": "TRIAL_CONVERSION",
        "status": "SUCCESS",
        "amountPesewas": 39900,
        "currency": "GHS",
        "attemptNo": 1,
        "environment": "LIVE",
        "planCode": "GROWTH",
        "interval": "MONTHLY",
        "periodStart": "2026-12-11T09:30:00Z",
        "periodEnd": "2027-01-11T09:30:00Z",
        "createdAt": "2026-12-11T09:30:00Z",
        "paidAt": "2026-12-11T09:30:07Z",
        "verifiedAt": "2026-12-11T09:30:07Z",
        "feesPesewas": null,
        "failureCode": null,
        "flagged": false,
        "flagReason": null,
        "refundedPesewas": 10000,
        "pendingRefundPesewas": 0,
        "refundableRemainingPesewas": 29900,
        "refundableUntil": "2027-03-11T09:30:07Z",
        "documents": [
          {
            "id": 501,
            "type": "INVOICE",
            "number": "SUB-2026-000001"
          },
          {
            "id": 502,
            "type": "RECEIPT",
            "number": "REC-2026-000001"
          }
        ]
      }
    ],
    "documents": [
      {
        "id": 504,
        "type": "RECEIPT",
        "number": "REC-2026-000002"
      },
      {
        "id": 503,
        "type": "INVOICE",
        "number": "SUB-2026-000002"
      },
      {
        "id": 502,
        "type": "RECEIPT",
        "number": "REC-2026-000001"
      },
      {
        "id": 501,
        "type": "INVOICE",
        "number": "SUB-2026-000001"
      },
      {
        "id": 507,
        "type": "CREDIT_NOTE",
        "number": "CN-2026-000001"
      }
    ],
    "adjustments": [
      {
        "id": 7001,
        "type": "REFUND",
        "amountPesewas": -10000,
        "paymentId": 9001,
        "reason": "Customer overcharged during onboarding",
        "actorEmail": "admin@shopfi.test",
        "createdAt": "2026-12-30T10:00:05Z"
      }
    ],
    "recentAudit": [
      {
        "id": 9112,
        "occurredAt": "2027-01-11T09:30:04Z",
        "actorType": "SYSTEM",
        "actorId": null,
        "actorEmail": null,
        "tenantId": 12,
        "tenantSlug": "acme",
        "action": "PAYMENT_FAILED",
        "reference": "sub-l-12-rn-1-1",
        "reason": null,
        "before": {
          "lifecycleStatus": "ACTIVE",
          "paymentStatus": "CURRENT",
          "accessState": "FULL"
        },
        "after": {
          "lifecycleStatus": "PAST_DUE",
          "paymentStatus": "RETRYING",
          "accessState": "GRACE"
        }
      },
      {
        "id": 9087,
        "occurredAt": "2026-12-30T10:00:05Z",
        "actorType": "PLATFORM_ADMIN",
        "actorId": 1,
        "actorEmail": "admin@shopfi.test",
        "tenantId": 12,
        "tenantSlug": "acme",
        "action": "REFUND_COMPLETED",
        "reference": "sub-l-12-tc-0-1",
        "reason": "Customer overcharged during onboarding",
        "before": {
          "refundedPesewas": 0
        },
        "after": {
          "refundedPesewas": 10000
        }
      }
    ]
  }
}
```

<!-- example: not-complimentary-error response CodeErrorEnvelope 409 -->
```json
{
  "description": "This subscription is not complimentary.",
  "data": {
    "code": "NOT_COMPLIMENTARY"
  }
}
```

### 11.5 Billing history and audit (FR-22, FR-26)

`GET .../subscriptions/{slug}/history?page&size`: payments, adjustments, documents and state transitions merged, newest first; `summary` is a fixed code, never free text.

<!-- example: subscription-history response HistoryEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "items": [
      {
        "at": "2027-01-11T09:30:04Z",
        "kind": "TRANSITION",
        "summary": "CHARGE_FAILED",
        "reference": "sub-l-12-rn-1-1",
        "amountPesewas": null,
        "id": null
      },
      {
        "at": "2027-01-11T09:30:00Z",
        "kind": "PAYMENT",
        "summary": "PAYMENT_FAILED",
        "reference": "sub-l-12-rn-1-1",
        "amountPesewas": 39900,
        "id": 9003
      },
      {
        "at": "2026-12-30T10:00:05Z",
        "kind": "DOCUMENT",
        "summary": "CREDIT_NOTE_ISSUED",
        "reference": "sub-l-12-tc-0-1",
        "amountPesewas": -10000,
        "id": 507
      },
      {
        "at": "2026-12-30T10:00:05Z",
        "kind": "ADJUSTMENT",
        "summary": "REFUND_COMPLETED",
        "reference": "sub-l-12-tc-0-1",
        "amountPesewas": -10000,
        "id": 7001
      },
      {
        "at": "2026-12-21T09:30:05Z",
        "kind": "PAYMENT",
        "summary": "PAYMENT_SUCCESS",
        "reference": "sub-l-12-up-1-1",
        "amountPesewas": 40645,
        "id": 9002
      },
      {
        "at": "2026-12-11T09:30:07Z",
        "kind": "PAYMENT",
        "summary": "PAYMENT_SUCCESS",
        "reference": "sub-l-12-tc-0-1",
        "amountPesewas": 39900,
        "id": 9001
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 6,
    "totalPages": 1
  }
}
```

`GET /api/v1/platform/billing/audit?tenant=<slug>&action=<CODE>&from=&to=&page&size` (all filters optional; `from` / `to` instants) and `GET /api/v1/platform/tenants/{slug}/billing-audit` (the same shape for one tenant), newest first, shapes in 17.5:

<!-- example: billing-audit-page response BillingAuditPageEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "items": [
      {
        "id": 9112,
        "occurredAt": "2027-01-11T09:30:04Z",
        "actorType": "SYSTEM",
        "actorId": null,
        "actorEmail": null,
        "tenantId": 12,
        "tenantSlug": "acme",
        "action": "PAYMENT_FAILED",
        "reference": "sub-l-12-rn-1-1",
        "reason": null,
        "before": {
          "lifecycleStatus": "ACTIVE",
          "paymentStatus": "CURRENT",
          "accessState": "FULL"
        },
        "after": {
          "lifecycleStatus": "PAST_DUE",
          "paymentStatus": "RETRYING",
          "accessState": "GRACE"
        }
      },
      {
        "id": 9087,
        "occurredAt": "2026-12-30T10:00:05Z",
        "actorType": "PLATFORM_ADMIN",
        "actorId": 1,
        "actorEmail": "admin@shopfi.test",
        "tenantId": 12,
        "tenantSlug": "acme",
        "action": "REFUND_COMPLETED",
        "reference": "sub-l-12-tc-0-1",
        "reason": "Customer overcharged during onboarding",
        "before": {
          "refundedPesewas": 0
        },
        "after": {
          "refundedPesewas": 10000
        }
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 2,
    "totalPages": 1
  }
}
```

### 11.6 Refunds

`POST /api/v1/platform/billing/payments/{id}/refund`: semantics, bounds and errors in 7.10 and 6.7. `200` (completed) or `202` (pending) `RefundResult` (`refund` + the payment's admin view with the new arithmetic).

### 11.7 Documents of every tenant (BILL-05 AC7)

`GET /api/v1/platform/billing/documents?tenant=<slug>&type=&number=&from=&to=&page&size`, `GET .../documents/{id}`, `GET .../documents/{id}/pdf` (10.4), `POST .../documents/{id}/resend`. `number` matches the exact number or a prefix.

<!-- example: platform-documents-page response DocumentPageEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "items": [
      {
        "id": 610,
        "type": "INVOICE",
        "number": "SUB-2027-000014",
        "issuedAt": "2027-02-03T08:00:04Z",
        "currency": "GHS",
        "reference": "sub-l-27-rn-2-1",
        "paymentId": 9120,
        "adjustmentId": null,
        "relatedDocumentId": null,
        "status": "PAID",
        "periodStart": "2027-02-03T08:00:00Z",
        "periodEnd": "2027-03-03T08:00:00Z",
        "plan": {
          "code": "GROWTH",
          "name": "Growth",
          "interval": "MONTHLY"
        },
        "lineItems": [
          {
            "description": "Growth plan, monthly, 3 Feb 2027 to 3 Mar 2027",
            "quantity": 1,
            "unitAmountPesewas": 34696,
            "amountPesewas": 34696
          }
        ],
        "amounts": {
          "netPesewas": 34696,
          "taxPesewas": 5204,
          "totalPesewas": 39900,
          "taxRateBps": 1500,
          "taxInclusive": true,
          "taxLabel": "VAT"
        },
        "business": {
          "name": "Example Business Ltd",
          "address": "1 Example Street, Accra",
          "tin": "C0000000000",
          "email": "billing@business.example",
          "phone": "+233200000000",
          "footer": "Thank you for your business."
        },
        "member": {
          "shopName": "Kente Cosmetics",
          "ownerName": "Ama Mensah",
          "ownerEmail": "ama@kente.example",
          "billingAddress": "12 Example Road, Kumasi"
        }
      },
      {
        "id": 507,
        "type": "CREDIT_NOTE",
        "number": "CN-2026-000001",
        "issuedAt": "2026-12-30T10:00:05Z",
        "currency": "GHS",
        "reference": "sub-l-12-tc-0-1",
        "paymentId": 9001,
        "adjustmentId": 7001,
        "relatedDocumentId": 501,
        "status": "CREDITED",
        "periodStart": "2026-12-11T09:30:00Z",
        "periodEnd": "2027-01-11T09:30:00Z",
        "plan": {
          "code": "GROWTH",
          "name": "Growth",
          "interval": "MONTHLY"
        },
        "lineItems": [
          {
            "description": "Refund of part of invoice SUB-2026-000001",
            "quantity": 1,
            "unitAmountPesewas": -10000,
            "amountPesewas": -10000
          }
        ],
        "amounts": {
          "netPesewas": -10000,
          "taxPesewas": 0,
          "totalPesewas": -10000,
          "taxRateBps": null,
          "taxInclusive": null,
          "taxLabel": null
        },
        "business": {},
        "member": {
          "shopName": "Acme Perfumes",
          "ownerEmail": "owner@acme.example"
        }
      },
      {
        "id": 501,
        "type": "INVOICE",
        "number": "SUB-2026-000001",
        "issuedAt": "2026-12-11T09:30:07Z",
        "currency": "GHS",
        "reference": "sub-l-12-tc-0-1",
        "paymentId": 9001,
        "adjustmentId": null,
        "relatedDocumentId": null,
        "status": "PAID",
        "periodStart": "2026-12-11T09:30:00Z",
        "periodEnd": "2027-01-11T09:30:00Z",
        "plan": {
          "code": "GROWTH",
          "name": "Growth",
          "interval": "MONTHLY"
        },
        "lineItems": [
          {
            "description": "Growth plan, monthly, 11 Dec 2026 to 11 Jan 2027",
            "quantity": 1,
            "unitAmountPesewas": 39900,
            "amountPesewas": 39900
          }
        ],
        "amounts": {
          "netPesewas": 39900,
          "taxPesewas": 0,
          "totalPesewas": 39900,
          "taxRateBps": null,
          "taxInclusive": null,
          "taxLabel": null
        },
        "business": {},
        "member": {
          "shopName": "Acme Perfumes",
          "ownerEmail": "owner@acme.example"
        }
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 3,
    "totalPages": 1
  }
}
```

### 11.8 Reconciliation and webhook event health

Sections 12.7 (`GET /webhook-events`, `POST /webhook-events/{id}/retry`) and 13 (`/reconciliation/**`).

## 12. The Paystack webhook receiver (FR-15, 4.3, M5, M6; BILL-01b)

The per-shop webhook (`POST /api/v1/webhook/{token}`, the shop's own secret, order-row idempotency) is unrelated and untouched. Subscription billing has its own receiver, its own secret (the platform credentials of the environment) and its own durable event table (`billing_webhook_events`).

### 12.1 Endpoint and exposure

`POST /api/v1/platform/billing/webhooks/paystack/{environment}`, `environment` = `test` | `live` (anything else: `404`). Paste the matching URL in the matching Paystack dashboard (section 4.4).

- **Security configuration**: an exact-path `permitAll` matcher for POST, placed BEFORE the `/api/v1/platform/**` SUPER_ADMIN rule (other methods fall to that rule: `401`). It must be verified that the tenant-resolution filter does not require a tenant for it (platform scope, no `Origin` needed) and that the platform Origin guard does not reject a header-less server-to-server POST. No cookie is read or set, no CORS headers, no tenant resolution, no authentication filter side effects.
- **Raw body**: the bytes are read ONCE as received (the signature covers the exact bytes; never re-serialize), capped at **64 KiB** (`413` beyond; the cap is checked while reading, not after).
- **Never log a body** (it carries the customer e-mail and authorization data), never log the signature or the secret, never put either in an error.

### 12.2 Signature

Header `x-paystack-signature` = lower-case hex of `HMAC-SHA512(rawBody, secretKey)` where `secretKey` is the secret of the environment in the PATH (Paystack has no separate webhook secret, M6). The comparison is constant-time (`MessageDigest.isEqual` on the decoded bytes). A missing, malformed or wrong signature, or a signature made with the secret of the OTHER environment, is `401` with ONLY a counter incremented (`shopfi.billing.webhook.invalid`): no event row, no DB write beyond the counter, no body logged. An environment with no stored keys is `404` (the same body as an unknown environment, nothing to compare against).
Worked example (documentation values; the fake Paystack signs with the secret configured for the fake): the raw body is exactly the single line below, the signature is the HMAC-SHA512 hex of those bytes with the secret shown.

```text
{"event":"charge.success","data":{"id":4099260516,"domain":"live","status":"success","reference":"sub-l-12-tc-0-1","amount":39900,"currency":"GHS","paid_at":"2026-12-11T09:30:06.000Z","channel":"card","customer":{"email":"owner@acme.example"},"authorization":{"authorization_code":"AUTH_docsexample","reusable":true,"channel":"card","card_type":"visa","last4":"4081","exp_month":"12","exp_year":"2029","bank":"TEST BANK","signature":"SIG_docsexample"}}}
```

secret (documentation value, not a key): `docs-example-secret-not-a-real-key`  
`x-paystack-signature: 45a0e8ee6ed1aab37614deaeeb549ef70b35d3926487a6cf2aa89f69a6003fd22e3ebb0986e48a3c076d418da14ff0b7a5a7f436a0e7a2ba6b1a71a0e2843823`

<!-- example: webhook-charge-success valid PaystackEvent -->
```json
{
  "event": "charge.success",
  "data": {
    "id": 4099260516,
    "domain": "live",
    "status": "success",
    "reference": "sub-l-12-tc-0-1",
    "amount": 39900,
    "currency": "GHS",
    "paid_at": "2026-12-11T09:30:06.000Z",
    "channel": "card",
    "customer": {
      "email": "owner@acme.example"
    },
    "authorization": {
      "authorization_code": "AUTH_docsexample",
      "reusable": true,
      "channel": "card",
      "card_type": "visa",
      "last4": "4081",
      "exp_month": "12",
      "exp_year": "2029",
      "bank": "TEST BANK",
      "signature": "SIG_docsexample"
    }
  }
}
```

### 12.3 Responses

| HTTP | When | Body |
|---|---|---|
| 200 | the signature is valid and the event is durably stored (committed) and queued, or it is a duplicate, or it is an event type we store and ignore | `{"description": null, "data": {"result": "Accepted" \| "Duplicate" \| "Ignored"}}` |
| 400 | valid signature but the body is not a JSON object with a string `event` and an object `data` (malformed) | `Invalid payload` |
| 401 | missing, malformed or wrong signature | `Invalid signature` |
| 404 | unknown environment segment, or no keys stored for it | `Not found` |
| 413 | body larger than 64 KiB | `Payload too large` |
| 503 | the database is unavailable (so that Paystack retries); **never 5xx for a business outcome** | |

<!-- example: webhook-ack-accepted response WebhookAckEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "result": "Accepted"
  }
}
```

<!-- example: webhook-ack-duplicate response WebhookAckEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "result": "Duplicate"
  }
}
```

<!-- example: webhook-ack-ignored response WebhookAckEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "result": "Ignored"
  }
}
```

<!-- example: webhook-invalid-signature response ErrorEnvelope 401 -->
```json
{
  "description": "Invalid signature",
  "data": null
}
```

<!-- example: webhook-unknown-environment response ErrorEnvelope 404 -->
```json
{
  "description": "Not found",
  "data": null
}
```

<!-- example: webhook-too-large response ErrorEnvelope 413 -->
```json
{
  "description": "Payload too large",
  "data": null
}
```

<!-- example: webhook-malformed response ErrorEnvelope 400 -->
```json
{
  "description": "Invalid payload",
  "data": null
}
```

<!-- example: webhook-not-an-event invalid PaystackEvent 400 -->
```json
{
  "hello": "world"
}
```

The `200` is sent only AFTER the event row is committed (`insert ... on conflict do nothing`), never after processing: processing is asynchronous (12.6). Paystack retries a non-2xx answer, so a database failure during the insert answers `503` and the event is delivered again.

### 12.4 Dedupe key

Paystack sends no event id on charge events (M17). The key is a hash of what identifies the event's subject and state:

```
ident     = first present of data.id, data.reference, data.refund_reference           (as text)
dedupeKey = lower-case hex SHA-256 of  event + "|" + ident + "|" + data.status        (data.status "" when absent)
fallback  = lower-case hex SHA-256 of the raw body   (when none of the three identifiers is present)
```

`(environment, dedupeKey)` is unique. The same event delivered 5 times is one row, answers `Duplicate` four times and is never reprocessed; a different status of the same subject (for example `success` after `failed`) is a different event.

| event | data.id or reference | data.status | dedupeKey |
|---|---|---|---|
| `charge.success` | 4099260516 | success | `f6d640f661abcb5bd1a7812378f52b33f2711c85e2ff148dd40bf99388955f5a` |
| `charge.success` | 4099260999 | success | `f6c27e7b96e7abe9f86dc2e98ab8ecb81d214c3e02f865f8dcf32d57bfc991b1` |
| `charge.failed` | 4099261000 | failed | `541b9ed6e2cf263ead677b6a25d2fd1e72bfaece4322894aaa49e12fa341c28f` |
| `refund.processed` | 3490187341 | processed | `a499f03367c992d75f6689327fd7dd8dcbc50803b3a69a4d9222a29c1be4644e` |

### 12.5 Which events do what

Every state change from an event re-verifies the subject with Paystack first (**BR-03, S3: the payload is never trusted**).

| `event` | Handling |
|---|---|
| `charge.success` | call Paystack `verify` for `data.reference`, then apply the VERIFIED result: payment `SUCCESS` (idempotent), subscription advanced by `SubscriptionStateService` (E2, E4, E8, E13 or the upgrade E14), `PaymentSucceeded` published (documents, mail) |
| `charge.failed` | `verify`, then apply the verified failure: payment `FAILED` (never over a `SUCCESS`), E5 / E6 per the policy, `PaymentFailed` published. (Paystack's documented event list should be re-checked at the first real smoke test, O19: a synchronous `charge_authorization` answer and reconciliation already cover failures if this event is never sent) |
| `refund.processed` | the refund request becomes `COMPLETED`, the adjustment row (negative) is inserted ONCE, the credit note is issued (idempotent by the refund key) |
| `refund.failed` | the refund request becomes `FAILED`; the amount is refundable again |
| `refund.pending`, `refund.processing` | stored and ignored (the request stays `PENDING`) |
| `charge.dispute.create` | **alert only** (metric, admin alert, audit row); no state, access or money change (O12) |
| every other event | stored as `IGNORED`, answer `Ignored`; never an error |

Two more inbound examples (the refund and the dispute events; Paystack's payload shapes are re-checked against its documentation when the fake's fixtures are written, BILL-01):

<!-- example: webhook-refund-processed valid PaystackEvent -->
```json
{
  "event": "refund.processed",
  "data": {
    "status": "processed",
    "transaction_reference": "sub-l-12-tc-0-1",
    "refund_reference": "3490187341",
    "amount": 10000,
    "currency": "GHS",
    "customer_note": "Refund",
    "merchant_note": "Refund"
  }
}
```

<!-- example: webhook-dispute-create valid PaystackEvent -->
```json
{
  "event": "charge.dispute.create",
  "data": {
    "id": 4411,
    "refund_amount": 39900,
    "currency": "GHS",
    "transaction": {
      "reference": "sub-l-12-tc-0-1",
      "amount": 39900
    },
    "status": "awaiting-merchant-response"
  }
}
```

The field checks of a verified charge (any mismatch: the payment is **flagged** (`flagged`, `flagReason`), an audit row and an admin alert are written, NO entitlement or state changes, the event ends `FAILED` with `failureClass VERIFY_MISMATCH`):

1. the reference has the billing prefix `sub-`, its environment letter equals the path environment, and a local payment row with that reference exists (an unknown billing reference is `UNKNOWN_REFERENCE`: the event is kept and a reconciliation `MISSING_LOCAL` finding is raised; a reference without the prefix belongs to something else on the same Paystack account and is `IGNORED`);
2. `amount` equals the stored expected amount of the row and `currency` is `GHS` (`AMOUNT_MISMATCH`, `CURRENCY_MISMATCH`);
3. the customer e-mail equals the e-mail stored on the payment row at creation (`CUSTOMER_MISMATCH`);
4. the transaction belongs to the same environment (live data on a test URL is rejected).

### 12.6 Ordering, duplicates, concurrency

- A payment's `SUCCESS` is **terminal and beats a late `FAILED`**. No event ever moves a payment backwards; `FAILED` -> `SUCCESS` happens only through verified provider data (a retry of a webhook after a timeout, a reconciliation heal).
- A refund event that arrives before its payment is `SUCCESS` locally stays `RECEIVED` and is retried by the worker until the payment is settled (then applied) or it ends `FAILED` after the attempts.
- Duplicate, out-of-order and concurrent deliveries (success after failed, failed after success, the same event 5 times, concurrent duplicates, a webhook racing a retry job and a member action) end in the same final state with ONE charge per reference and ONE invoice: every apply starts with `SELECT ... FOR UPDATE` on the subscription row.
- The worker is its own thread (`SmartLifecycle`, bounded batch, backoff, retry); an event that keeps failing ends `FAILED` after 5 attempts (`failureClass POISON`) and is listed in the console; it never blocks other events. A failing round survives.
- An optional IP allow-list (`webhookIpAllowlistEnabled`, `webhookIpAllowlist`, O29) is checked before the body is read; a request from another address is `404`. HMAC remains the primary control.

### 12.7 Webhook event health (console)

`GET /api/v1/platform/billing/webhook-events?environment=&status=&eventType=&page&size` (newest first) lists rows without any payload body: `status` `RECEIVED | PROCESSING | PROCESSED | IGNORED | FAILED`, `attempts`, `failureClass` (`VERIFY_MISMATCH`, `UNKNOWN_REFERENCE`, `PROVIDER_UNAVAILABLE`, `INTERNAL`, `POISON`), `reference`, `dedupeKey`, timestamps.
`POST .../webhook-events/{id}/retry` (audited, SUPER_ADMIN) re-queues a `FAILED` event (`202`, status back to `RECEIVED`); any other status is `409 WEBHOOK_EVENT_NOT_RETRYABLE`.

<!-- example: webhook-events-page response WebhookEventPageEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "items": [
      {
        "id": 5002,
        "environment": "LIVE",
        "eventType": "charge.success",
        "status": "FAILED",
        "attempts": 5,
        "failureClass": "VERIFY_MISMATCH",
        "reference": "sub-l-31-rn-1-1",
        "dedupeKey": "f6c27e7b96e7abe9f86dc2e98ab8ecb81d214c3e02f865f8dcf32d57bfc991b1",
        "receivedAt": "2027-01-20T10:00:03Z",
        "processedAt": null
      },
      {
        "id": 5001,
        "environment": "LIVE",
        "eventType": "charge.success",
        "status": "PROCESSED",
        "attempts": 1,
        "failureClass": null,
        "reference": "sub-l-12-tc-0-1",
        "dedupeKey": "f6d640f661abcb5bd1a7812378f52b33f2711c85e2ff148dd40bf99388955f5a",
        "receivedAt": "2026-12-11T09:30:08Z",
        "processedAt": "2026-12-11T09:30:09Z"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 2,
    "totalPages": 1
  }
}
```

<!-- example: webhook-event-retried response WebhookEventEnvelope 202 -->
```json
{
  "description": null,
  "data": {
    "id": 5002,
    "environment": "LIVE",
    "eventType": "charge.success",
    "status": "RECEIVED",
    "attempts": 0,
    "failureClass": null,
    "reference": "sub-l-31-rn-1-1",
    "dedupeKey": "1111111111111111111111111111111111111111111111111111111111111111",
    "receivedAt": "2027-01-20T10:00:03Z",
    "processedAt": null
  }
}
```

<!-- example: webhook-event-not-retryable response CodeErrorEnvelope 409 -->
```json
{
  "description": "Only failed events can be retried.",
  "data": {
    "code": "WEBHOOK_EVENT_NOT_RETRYABLE"
  }
}
```

### 12.8 Metrics and alerts (BILL-01b AC11)

`shopfi.billing.webhook.received|invalid|duplicate|failed`, `shopfi.billing.job.lag|failures`, `shopfi.billing.reconcile.findings`, outbox depth; alert rules in `prometheus-alerts.yml`: invalid-signature bursts, failed events, job lag (a billing-managed trial overdue), stuck reconciliation, open findings.

## 13. Reconciliation (FR-15, 4.3; BILL-01b)

A dedicated scheduler (`SmartLifecycle`, survives a failing round, two-key advisory leader lock) compares our records with Paystack so that a dropped webhook or a lost response never leaves money or access wrong. Every run is recorded (`billing_reconciliation_runs`: kind, environment, trigger, counts).

1. **Incremental**, every `reconcileIntervalMinutes`: (a) every local payment stuck `INITIATED` / `PENDING` longer than a threshold is `verify`-ed and healed through the SAME apply path as the webhook; (b) the Paystack transaction list of the last `reconcileLookbackHours` is read (only references with the billing prefix and the environment's letter) and compared.
2. **Full scan**, nightly, over `reconcileFullScanDays`.
3. **Manual**: `POST /api/v1/platform/billing/reconciliation/run` `{environment?, full?}` (SUPER_ADMIN, throttled): `202` with the run (`RUNNING`); `409 RECONCILIATION_IN_PROGRESS` while one runs for that environment.

| Finding `type` | Meaning | Auto-healed? |
|---|---|---|
| `MISSING_LOCAL` | Paystack has a transaction with our prefix that no local row explains | **never**: it is reported only; the system never creates a money row from an unknown remote transaction |
| `AMOUNT_MISMATCH` | amounts differ between the local row and Paystack | reported; the row is flagged; no entitlement change |
| `STATUS_MISMATCH` | statuses differ. Remote `success` with a local `INITIATED` / `PENDING` row and matching amount is healed by the apply path (and counted `healed`); a local `SUCCESS` that Paystack reports as failed, reversed or absent is reported only (money is never moved backwards automatically) | partially, as stated |
| `ORPHAN_REFUND` | a refund exists at Paystack with no local refund request | reported only |

A finding is `OPEN` or `RESOLVED`; resolving needs a reason (5..500 characters) and is audited; a resolved finding is not raised again for the same reference and state. Tests on the fake: a dropped webhook is healed by reconciliation; a wrong-amount remote success becomes a finding, never an entitlement.

`GET /api/v1/platform/billing/reconciliation/findings?status=&type=&environment=&page&size`, `POST .../findings/{id}/resolve` `{reason}`, `GET .../reconciliation/runs`:

<!-- example: findings-page response ReconciliationFindingPageEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "items": [
      {
        "id": 17,
        "type": "MISSING_LOCAL",
        "status": "OPEN",
        "environment": "LIVE",
        "reference": "sub-l-31-rn-1-1",
        "tenantSlug": "zuri",
        "local": null,
        "remote": {
          "status": "success",
          "amountPesewas": 14900
        },
        "detectedAt": "2027-01-20T10:15:00Z",
        "runId": 88,
        "resolvedAt": null,
        "resolvedBy": null,
        "resolution": null
      },
      {
        "id": 16,
        "type": "AMOUNT_MISMATCH",
        "status": "RESOLVED",
        "environment": "LIVE",
        "reference": "sub-l-27-rn-2-1",
        "tenantSlug": "kente",
        "local": {
          "status": "PENDING",
          "amountPesewas": 39900
        },
        "remote": {
          "status": "success",
          "amountPesewas": 3990
        },
        "detectedAt": "2027-01-18T10:15:00Z",
        "runId": 85,
        "resolvedAt": "2027-01-18T12:00:00Z",
        "resolvedBy": "admin@shopfi.test",
        "resolution": "Paystack confirmed the remote amount was a test charge"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 2,
    "totalPages": 1
  }
}
```

<!-- example: finding-resolve-request valid ResolveFindingRequest -->
```json
{
  "reason": "Checked in the Paystack dashboard, charge belongs to another product"
}
```

<!-- example: finding-resolve-request-short invalid ResolveFindingRequest 400 -->
```json
{
  "reason": "ok"
}
```

<!-- example: finding-resolve-result response ReconciliationFindingEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "id": 17,
    "type": "MISSING_LOCAL",
    "status": "RESOLVED",
    "environment": "LIVE",
    "reference": "sub-l-31-rn-1-1",
    "tenantSlug": "zuri",
    "local": null,
    "remote": {
      "status": "success",
      "amountPesewas": 14900
    },
    "detectedAt": "2027-01-20T10:15:00Z",
    "runId": 88,
    "resolvedAt": "2027-01-20T11:00:00Z",
    "resolvedBy": "admin@shopfi.test",
    "resolution": "Checked in the Paystack dashboard, charge belongs to another product"
  }
}
```

<!-- example: finding-already-resolved response CodeErrorEnvelope 409 -->
```json
{
  "description": "This finding is already resolved.",
  "data": {
    "code": "FINDING_ALREADY_RESOLVED"
  }
}
```

<!-- example: reconcile-run-request valid ReconciliationRunRequest -->
```json
{
  "environment": "LIVE",
  "full": false
}
```

<!-- example: reconcile-run-request-bad-env invalid ReconciliationRunRequest 400 -->
```json
{
  "environment": "STAGING"
}
```

<!-- example: reconcile-run-result response ReconciliationRunEnvelope 202 -->
```json
{
  "description": null,
  "data": {
    "id": 88,
    "kind": "INCREMENTAL",
    "environment": "LIVE",
    "trigger": "MANUAL",
    "startedAt": "2027-01-20T10:15:00Z",
    "finishedAt": null,
    "status": "RUNNING",
    "checked": 0,
    "healed": 0,
    "findings": 0
  }
}
```

<!-- example: reconcile-in-progress response CodeErrorEnvelope 409 -->
```json
{
  "description": "A reconciliation run is already in progress.",
  "data": {
    "code": "RECONCILIATION_IN_PROGRESS"
  }
}
```

<!-- example: reconcile-runs response ReconciliationRunsEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "runs": [
      {
        "id": 88,
        "kind": "INCREMENTAL",
        "environment": "LIVE",
        "trigger": "MANUAL",
        "startedAt": "2027-01-20T10:15:00Z",
        "finishedAt": "2027-01-20T10:15:09Z",
        "status": "OK",
        "checked": 212,
        "healed": 1,
        "findings": 1
      },
      {
        "id": 87,
        "kind": "INCREMENTAL",
        "environment": "LIVE",
        "trigger": "SCHEDULE",
        "startedAt": "2027-01-20T10:00:00Z",
        "finishedAt": "2027-01-20T10:00:04Z",
        "status": "OK",
        "checked": 198,
        "healed": 0,
        "findings": 0
      }
    ],
    "openFindings": 1
  }
}
```

## 14. Reports and analytics (FR-25; BILL-07)

SUPER_ADMIN only. All figures are computed from the immutable ledger and the subscription history (state transitions with their price snapshots), **not from current projections alone**, so a past range gives the same answer later (BILL-07 AC3). Money is integer pesewas. TEST-environment rows are excluded from a LIVE report and vice versa; `environment` defaults to the active environment.

### 14.1 Endpoints

| Endpoint | Query | Response |
|---|---|---|
| `GET /api/v1/platform/billing/reports/summary` | `from`, `to` (required instants, `from < to`, range <= 24 months), `environment`, `planId`, `interval` | `ReportsSummary` |
| `GET /api/v1/platform/billing/reports/timeseries` | `metric` (`MRR`, `REVENUE`, `NEW`, `CANCELLED`, `FAILED`, `CONVERSION`), `granularity` (`day`, `week`, `month`), `from`, `to`, `environment`, `planId`, `interval` | `Timeseries` |
| `GET /api/v1/platform/billing/reports/export.csv` | `from`, `to`, `environment`, `planId`, `interval` | CSV (14.4) |

The range is `[from, to)` in UTC. An invalid date, enum or range is `400` with the field map; there is no member PII in any aggregate (slugs and plan codes only appear in the export). Time-series buckets are UTC (`day` = 00:00Z, `week` = Monday 00:00Z, `month` = the 1st 00:00Z), the first bucket is the one containing `from`, the last the one containing `to - 1 s`, values only count rows inside `[from, to)`, gaps are zero-filled (`CONVERSION` with no trial ended in a bucket is `null`); point counts are bounded (`day` <= 92 days, `week` <= 104 weeks, `month` <= 24 months).

### 14.2 Definitions (exact; the API returns them in `definitions` for the console's info popover)

| Metric | Definition |
|---|---|
| gross collected | sum of `SUCCESS` payments with `paidAt` in the range, purposes other than `CARD_VERIFY`, in the environment (and plan / interval filter by the payment's plan and interval) |
| refunded | sum of refunds `COMPLETED` with `completedAt` in the range (same filters, by the refunded payment) |
| net revenue | gross collected - refunded (may be negative in a bucket) |
| fees | sum of Paystack fees recorded on those payments when Paystack returned them, otherwise `null`; shown separately, never subtracted from revenue or MRR (O21) |
| MRR (as at `to`) | sum over subscriptions with `lifecycleStatus` `ACTIVE` or `PAST_DUE`, `billingMode PAYSTACK`, `complimentary false`, of the locked price per month: `MONTHLY` the locked amount, `ANNUAL` `round_half_up(locked / 12)`. Trialing, ended, complimentary and `MANUAL` subscriptions are excluded |
| active subscriptions (as at `to`) | the count of the subscriptions counted in MRR |
| new subscriptions | subscriptions whose FIRST successful non-verification payment is in the range |
| cancellations | `scheduled`: cancellation requests recorded in the range; `effective`: subscriptions that became `CANCELED` or `EXPIRED` in the range |
| failed payments | payment attempts with status `FAILED` created in the range (count and amount) |
| trial conversion | of the trials whose `trialEndsAt` is in the range (`trialsEnded`), those with a successful `TRIAL_CONVERSION` payment (`converted`); `rateBps = round_half_up(converted x 10000 / trialsEnded)`, `null` when none ended |
| outstanding (as at `to`) | subscriptions with payment status `RETRYING` or `FAILED`, and the amount of their open charge (`amountAtRisk`) |
| revenue by plan | gross, refunded and net grouped by the plan of the payment |
| ARPU | `round_half_up(MRR / active subscriptions)`, `null` when there are none |

Time-series metrics: `MRR` the MRR as at the end of each bucket, `REVENUE` net revenue in the bucket (unit `PESEWAS`), `NEW`, `CANCELLED` (effective), `FAILED` (count) in `COUNT`, `CONVERSION` the trial conversion rate of trials ended in the bucket in `BPS`.

### 14.3 Golden dataset (hand-computed; BILL-07 AC3 uses it, `check_examples.py` recomputes the examples from it)

Range `[2026-11-30T00:00:00Z, 2027-01-04T00:00:00Z)`, environment `LIVE`, no plan or interval filter. Subscriptions as at `to` (`lockedPesewas` is the lock of the interval shown; `trialEndsAt`, `cancelRequestedAt`, `endedAt` are the history facts used by the metrics):

| slug | plan | interval | lockedPesewas | lifecycle | payment | mode | complimentary | trialEndsAt | cancelRequestedAt | endedAt |
|---|---|---|---|---|---|---|---|---|---|---|
| acme | SCALE | MONTHLY | 99900 | ACTIVE | CURRENT | PAYSTACK | no | `2026-12-11T09:30:00Z` | - | - |
| lumen | STARTER | MONTHLY | 14900 | ACTIVE | CURRENT | PAYSTACK | no | `2026-12-05T10:00:00Z` | `2026-12-27T10:00:00Z` | - |
| kente | GROWTH | ANNUAL | 383040 | ACTIVE | CURRENT | PAYSTACK | no | `2026-09-19T08:00:00Z` | - | - |
| zuri | GROWTH | MONTHLY | 39900 | PAST_DUE | RETRYING | PAYSTACK | no | `2026-12-28T10:00:00Z` | - | - |
| quin | STARTER | MONTHLY | 14900 | CANCELED | NONE | PAYSTACK | no | `2026-12-20T10:00:00Z` | `2026-12-15T10:00:00Z` | `2026-12-20T10:00:00Z` |
| nana | SCALE | MONTHLY | 99900 | ACTIVE | NONE | PAYSTACK | yes | - | - | - |
| olu | GROWTH | MONTHLY | 39900 | ACTIVE | NONE | MANUAL | no | - | - | - |
| tori | STARTER | MONTHLY | 14900 | TRIALING | CURRENT | PAYSTACK | no | `2027-01-10T10:00:00Z` | - | - |

Payments (all `LIVE` unless the `env` column says otherwise; `CARD_VERIFY` and the `TEST` row are excluded by the definitions; ids 9001 and 9002 are the payments of the story):

| id | slug | purpose | plan | status | amount | at | env |
|---|---|---|---|---|---|---|---|
| 9200 | kente | TRIAL_CONVERSION | GROWTH | SUCCESS | 39900 | `2026-10-03T08:00:05Z` | LIVE |
| 9201 | kente | RENEWAL | GROWTH | SUCCESS | 383040 | `2026-12-03T08:00:05Z` | LIVE |
| 9202 | lumen | TRIAL_CONVERSION | STARTER | SUCCESS | 14900 | `2026-12-05T10:00:06Z` | LIVE |
| 9001 | acme | TRIAL_CONVERSION | GROWTH | SUCCESS | 39900 | `2026-12-11T09:30:07Z` | LIVE |
| 9206 | tori | CARD_VERIFY | STARTER | SUCCESS | 100 | `2026-12-20T10:00:04Z` | LIVE |
| 9002 | acme | UPGRADE | SCALE | SUCCESS | 40645 | `2026-12-21T09:30:05Z` | LIVE |
| 9203 | zuri | TRIAL_CONVERSION | GROWTH | FAILED | 39900 | `2026-12-28T10:00:03Z` | LIVE |
| 9205 | olu | RENEWAL | GROWTH | SUCCESS | 12345 | `2026-12-09T11:00:00Z` | TEST |

Refunds:

| id | paymentId | amount | status | completedAt |
|---|---|---|---|---|
| 3001 | 9001 | 10000 | COMPLETED | `2026-12-30T10:00:05Z` |

Hand computation: gross = 39900 (acme conversion) + 40645 (acme upgrade) + 383040 (kente renewal) + 14900 (lumen conversion) = **478485**; refunded **10000**; net **468485**. MRR = 99900 + 14900 + round_half_up(383040 / 12) = 31920 + 39900 (zuri, `PAST_DUE` still counts) = **186620** over **4** active subscriptions, ARPU = round_half_up(186620 / 4) = **46655**. New subscriptions **2** (acme, lumen; kente paid first in October). Trials ended in the range: acme, lumen, zuri, quin = 4, converted 2, `rateBps` **5000**. Cancellations: requested 2 (lumen, quin), effective 1 (quin). Failed payments: 1 attempt of 39900 (zuri). Outstanding: 1 subscription, 39900. Revenue by plan: STARTER 14900, GROWTH 422940 gross (10000 refunded, 412940 net), SCALE 40645; they add up to the totals. The weekly revenue buckets (Mondays) are 397940 (kente 383040 + lumen 14900), 39900, 0, 40645, -10000 and add up to the net.

<!-- example: reports-summary response ReportsSummaryEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "range": {
      "from": "2026-11-30T00:00:00Z",
      "to": "2027-01-04T00:00:00Z"
    },
    "filters": {
      "environment": "LIVE",
      "planId": null,
      "interval": null
    },
    "revenue": {
      "grossCollectedPesewas": 478485,
      "refundedPesewas": 10000,
      "netRevenuePesewas": 468485,
      "feesPesewas": null
    },
    "mrrPesewas": 186620,
    "activeSubscriptions": 4,
    "newSubscriptions": 2,
    "cancellations": {
      "scheduled": 2,
      "effective": 1
    },
    "failedPayments": {
      "count": 1,
      "amountPesewas": 39900
    },
    "trialConversion": {
      "trialsEnded": 4,
      "converted": 2,
      "rateBps": 5000
    },
    "outstanding": {
      "subscriptions": 1,
      "amountAtRiskPesewas": 39900
    },
    "revenueByPlan": [
      {
        "planCode": "STARTER",
        "planName": "Starter",
        "grossPesewas": 14900,
        "refundedPesewas": 0,
        "netPesewas": 14900
      },
      {
        "planCode": "GROWTH",
        "planName": "Growth",
        "grossPesewas": 422940,
        "refundedPesewas": 10000,
        "netPesewas": 412940
      },
      {
        "planCode": "SCALE",
        "planName": "Scale",
        "grossPesewas": 40645,
        "refundedPesewas": 0,
        "netPesewas": 40645
      }
    ],
    "arpuPesewas": 46655,
    "definitions": {
      "revenue": "Gross: sum of SUCCESS payments with paidAt in [from, to), purposes other than CARD_VERIFY, in the filtered environment; refunded: sum of COMPLETED refunds with completedAt in the range; net = gross - refunded.",
      "mrr": "As at `to`: sum over subscriptions that are ACTIVE or PAST_DUE, billingMode PAYSTACK, not complimentary, of the locked price per month (ANNUAL: round_half_up(locked / 12)). Trialing, ended and MANUAL subscriptions are excluded.",
      "activeSubscriptions": "As at `to`: the count of the subscriptions counted in MRR.",
      "newSubscriptions": "Subscriptions whose first successful non-verification payment is in the range.",
      "cancellations": "scheduled: cancellation requests recorded in the range; effective: subscriptions that became CANCELED or EXPIRED in the range.",
      "failedPayments": "Payment attempts with status FAILED created in the range (count and amount).",
      "trialConversion": "Of the trials whose trialEndsAt is in the range: converted = those with a successful TRIAL_CONVERSION payment; rateBps = round_half_up(converted x 10000 / trialsEnded), null when no trial ended.",
      "outstanding": "As at `to`: subscriptions with payment status RETRYING or FAILED and the amount of their open charge.",
      "arpu": "round_half_up(mrr / activeSubscriptions), null when there are none."
    }
  }
}
```

<!-- example: reports-timeseries response TimeseriesEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "metric": "REVENUE",
    "granularity": "week",
    "unit": "PESEWAS",
    "range": {
      "from": "2026-11-30T00:00:00Z",
      "to": "2027-01-04T00:00:00Z"
    },
    "points": [
      {
        "bucketStart": "2026-11-30T00:00:00Z",
        "value": 397940
      },
      {
        "bucketStart": "2026-12-07T00:00:00Z",
        "value": 39900
      },
      {
        "bucketStart": "2026-12-14T00:00:00Z",
        "value": 0
      },
      {
        "bucketStart": "2026-12-21T00:00:00Z",
        "value": 40645
      },
      {
        "bucketStart": "2026-12-28T00:00:00Z",
        "value": -10000
      }
    ]
  }
}
```

<!-- example: reports-validation-error response ValidationErrorEnvelope 400 -->
```json
{
  "description": "Validation failed",
  "data": {
    "to": "range must not exceed 24 months",
    "granularity": "must be one of day, week, month"
  }
}
```

### 14.4 CSV export

`text/csv; charset=utf-8`, `Content-Disposition: attachment; filename="billing-<from>-<to>.csv"`, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`. One header row, then one row per successful payment and per completed refund of the range: `kind,id,reference,tenantSlug,planCode,purpose,amountPesewas,currency,occurredAt`. **Formula-injection safe**: every TEXT cell that starts with `=`, `+`, `-`, `@`, a tab or a carriage return is prefixed with a single quote (`'`); amount cells are integers produced by the server (a refund amount is a plain negative integer and is the only cell allowed to start with `-`), fields containing a comma, quote or line break are quoted per RFC 4180.

```csv
kind,id,reference,tenantSlug,planCode,purpose,amountPesewas,currency,occurredAt
PAYMENT,9001,sub-l-12-tc-0-1,acme,GROWTH,TRIAL_CONVERSION,39900,GHS,2026-12-11T09:30:07Z
REFUND,3001,sub-l-12-tc-0-1,acme,GROWTH,TRIAL_CONVERSION,-10000,GHS,2026-12-30T10:00:05Z
```

Performance (BILL-07 AC4): indexed queries or a daily rollup refreshed by the billing scheduler, proved <= 300 ms on a generated 50k-subscription / 500k-payment dataset with an `EXPLAIN`-backed test, no N+1.

## 15. Scheduled jobs (S8, M9)

All billing jobs are MASTER-level (`TenantScope.PLATFORM`): they never iterate tenant schemas, never inherit or leave a `TenantContext` (cleared in `finally`), never call `EntitlementService` or any plan-gated service (a call outside HTTP is treated as a write and would be refused), run on their own `SmartLifecycle` thread so a failing round never stops the scheduler (precedent `OutboxDrainScheduler`, `CustomDomainReverifyScheduler`), use bounded batches, one transaction per subscription with `SELECT ... FOR UPDATE SKIP LOCKED` on the subscription row, and take a two-key advisory leader lock (`pg_advisory_xact_lock(int4, int4)`, namespaces defined in one place, BILL-01c) so a second instance is safe. Every round is metered (`shopfi.billing.job.lag|failures`).

| Job | Interval | Does |
|---|---|---|
| billing round | `billingRunIntervalSeconds` | trial conversion charges (7.2), renewals (the scheduled change is applied first, 7.7), price change application (6.4), retries (8.4), grace expiry (E7), cancellation effect at period end (E11), term end (E12), complimentary end (E17), `NO_CARD` entry (E20), card-verification refunds still owed |
| reminder scheduling | same round | creates the outbox rows of the reminder kinds at their instants (section 16); unsent reminders of an edited setting or trial end are rescheduled or cancelled |
| webhook event worker | continuous | processes `billing_webhook_events` (12.6) |
| reconciliation | `reconcileIntervalMinutes`, nightly full | section 13 |
| outbox drain | seconds | sends `billing_outbox` mail through `MailTransport` (16.1) |
| document issuing | in the transaction of the payment | not a job: section 10 |

Order inside one round: retries before grace expiry (an attempt clamped to `graceEndsAt` runs first); a pending payment is resolved by `verify` before any new attempt for the same subscription; the scheduled plan change is applied before the renewal charge.

## 16. Notifications (FR-21, 2.8; BILL-02, BILL-03, BILL-04, BILL-05)

### 16.1 Mechanism and rules

Billing data is MASTER data, so billing notifications do not use the tenant outbox, the tenant `Notification` rows or the `EmailService` payment methods (M4). They use a master `billing_outbox` (rows unique by `(kind, dedupe_key)`, retry with backoff, DONE purged after 14 days, FAILED kept), a `BillingMailService` on `MailTransport` (Thymeleaf templates, plain text and HTML), and its own drain scheduler. The **recipient is the tenant owner e-mail** (`tenants.owner_email`); the in-app side is the banner data of `GET /admin/billing/summary` (section 9.3), not a tenant-schema row.

- Sent after the commit of the change that caused them (the outbox row is written in the SAME transaction); restart-safe and delivered once (a kill between commit and send still delivers once).
- `emailEnabled[kind] = false` suppresses only the e-mail; banners are driven by state and are never switched off.
- An e-mail never contains card data, an authorization, a secret or a long-lived secret link; links go to `/admin/settings/billing` on the tenant host (host from configuration). The address is never logged.
- A reminder for an instant that is already past when it would be scheduled is skipped (never sent early or late); a charge reminder (`TRIAL_REMINDER`, `TRIAL_ENDS_TODAY`, `RENEWAL_REMINDER`) is not sent while `cancelAtPeriodEnd` is set or the subscription is complimentary. Changing a reminder setting affects only reminders not yet sent; an edited trial end reschedules and cancels the pending reminders.
- Template variables are an allow-list: `shopName`, `planName`, `interval`, `amount` (from pesewas, formatted by the template), `dueDate`, `trialEndsAt`, `daysLeft`, `graceEndsAt`, `nextRetryAt`, `cardBrand`, `cardLast4`, `cardExpiry`, `invoiceNumber`, `documentType`, `effectiveAt`, `oldAmount`, `newAmount`, `billingUrl`.

### 16.2 Catalogue

`E` = e-mail (switchable by `emailEnabled`), `B` = in-app banner kind (9.3). Timing is the setting that controls it; "event" = immediately after the commit.

| `kind` | Sent when | Channels | Timing | Variables | Dedupe key |
|---|---|---|---|---|---|
| `TRIAL_REMINDER` | N days before `trialEndsAt` (asks to add a card when none, `MANUAL`) | E, B `TRIAL_ENDING` / `TRIAL_NO_CARD` | `trialReminderDaysBefore` | shopName, planName, trialEndsAt, daysLeft, amount, cardLast4 | `trial-reminder:{tenantId}:{trialEndsAtEpoch}:{N}` |
| `TRIAL_ENDS_TODAY` | the UTC day the trial ends | E, B | at 00:00 UTC of that day | shopName, trialEndsAt, amount | `trial-ends-today:{tenantId}:{trialEndsAtEpoch}` |
| `TRIAL_ENDED` | the trial ended WITHOUT a charge (cancelled, or no card) | E, B `ACCESS_RESTRICTED` | event (E11, E20) | shopName, trialEndsAt | `trial-ended:{tenantId}:{trialEndsAtEpoch}` |
| `PAYMENT_SUCCEEDED` | a charge became `SUCCESS` | E | event | shopName, planName, amount, invoiceNumber | `payment-succeeded:{paymentId}` |
| `PAYMENT_FAILED` | the first attempt of a charge failed (states the grace end and the next retry date) | E, B `PAYMENT_FAILED_GRACE` | event (E5) | shopName, amount, graceEndsAt, nextRetryAt, cardLast4 | `payment-failed:{paymentId}` |
| `RETRY_SCHEDULED` | a later attempt failed and another is scheduled | E | event (E6) | amount, nextRetryAt, graceEndsAt | `retry-scheduled:{paymentId}` |
| `GRACE_STARTED` | grace began without a failed charge (`NO_CARD`) | E, B | event (E17 / E20) | shopName, graceEndsAt | `grace-started:{tenantId}:{graceStartedAtEpoch}` |
| `GRACE_REMINDER` | N days after the first failure, while unpaid | E, B | `graceReminderOffsetsDays` | amount, graceEndsAt, nextRetryAt | `grace-reminder:{tenantId}:{graceStartedAtEpoch}:{N}` |
| `GRACE_ENDING` | N days before grace ends | E, B | `graceEndingReminderDaysBefore` | amount, graceEndsAt | `grace-ending:{tenantId}:{graceStartedAtEpoch}:{N}` |
| `ACCESS_RESTRICTED` | access became `READ_ONLY` (grace over, manual restriction) | E, B `ACCESS_RESTRICTED` | event (E7, E15) | shopName, amount, billingUrl | `access-restricted:{tenantId}:{graceStartedAtEpoch\|auditId}` |
| `ACCESS_RESTORED` | access returned to `FULL` after being `GRACE` / `READ_ONLY` | E | event (E8) | shopName, planName | `access-restored:{tenantId}:{paymentId}` |
| `RENEWAL_REMINDER` | N days before the next charge | E | `renewalReminderDaysBefore` | planName, amount, dueDate, cardLast4 | `renewal-reminder:{tenantId}:{periodEndEpoch}:{N}` |
| `UPGRADE_APPLIED` | an upgrade was charged and applied | E | event (E14) | planName, amount, invoiceNumber | `upgrade-applied:{paymentId}` |
| `DOWNGRADE_SCHEDULED` | a downgrade or interval change was recorded | E | event | planName, interval, effectiveAt | `downgrade-scheduled:{tenantId}:{changeSequence}` |
| `DOWNGRADE_APPLIED` | the pending change took effect at renewal | E | event (E14 at renewal) | planName, interval, amount | `downgrade-applied:{tenantId}:{changeSequence}` |
| `CANCELLATION_SCHEDULED` | non-renewal was scheduled | E, B | event (E9) | effectiveAt | `cancellation-scheduled:{tenantId}:{eventId}` |
| `CANCELLATION_REVERSED` | reactivated | E | event (E10) | planName | `cancellation-reversed:{tenantId}:{eventId}` |
| `SUBSCRIPTION_ENDED` | the subscription ended (E11 after a period, E12) | E, B | event | effectiveAt | `subscription-ended:{tenantId}:{endedAtEpoch}` |
| `INVOICE_AVAILABLE` | a document was issued | E, B | event | invoiceNumber, documentType, billingUrl | `invoice-available:{documentId}` |
| `REFUND_ISSUED` | a refund completed | E | event | amount, invoiceNumber | `refund-issued:{refundId}` |
| `PRICE_CHANGE_NOTICE` | a price change notice was created | E, B | at the plan save; applies `priceChangeNoticeDays` later | planName, oldAmount, newAmount, effectiveAt | `price-change:{tenantId}:{noticeId}` |
| `COMPLIMENTARY_STARTED` | a complimentary plan was granted | E | event (E16) | planName, effectiveAt (the end, if any) | `complimentary-started:{tenantId}:{eventId}` |
| `COMPLIMENTARY_ENDING` | N days before `until` | E, B | `renewalReminderDaysBefore` | planName, effectiveAt, cardLast4 | `complimentary-ending:{tenantId}:{untilEpoch}:{N}` |
| `COMPLIMENTARY_ENDED` | the complimentary term ended | E | event (E17) | planName | `complimentary-ended:{tenantId}:{untilEpoch}` |
| `CARD_EXPIRING` | the default card expires this or next month | E, B | at the first round of the month before expiry | cardBrand, cardLast4, cardExpiry | `card-expiring:{authorizationId}` |
| `CARD_UPDATED` | the default card was replaced | E | event | cardBrand, cardLast4 | `card-updated:{authorizationId}` |

Admin-facing conditions (flagged payment, failed webhook event, open reconciliation finding, dispute, stuck round) are NOT e-mails: they are metrics, alerts and console rows (sections 12.8, 13).

## 17. Check order, never-gated list, errors, caching, audit

### 17.1 Check order on a billing endpoint

Checks run in this order; the first failure answers. It extends [plans.md](plans.md) section 14.4 **without step 5**: billing endpoints have no plan gate.

1. **Tenant resolution** (tenant routes only; the platform, onboarding and webhook routes have none): no tenant `400`, unknown `404`, **suspended `423`**.
2. **Authentication**: `401`.
3. **Authorization**: `403` (role matchers, `@PreAuthorize`; FRONT_DESK on a money route, non-SUPER_ADMIN on the platform).
4. **Body parsing and validation**: `400` (`Request body is missing or malformed.`, `Validation failed` + field map, `Idempotency-Key` presence and format, query parameter validation).
5. **Object-level lookup**: `404` for an id or slug that does not exist OR belongs to another tenant (identical body, S5).
6. **State, version and idempotency checks**: `409` (`SUBSCRIPTION_VERSION_CONFLICT`, `CHANGE_NOT_ALLOWED`, `ACTION_NOT_ALLOWED`, `PAYMENT_PENDING`, `IDEMPOTENCY_KEY_REUSED`, `BILLING_NOT_CONFIGURED`, ...). Throttling (`429`) is applied after authorization and before the body is processed.
7. **Provider and business outcome**: the charge, with `PAYMENT_FAILED` `409`, `202 PAYMENT_PENDING` for an unknown outcome, `503 PROVIDER_UNAVAILABLE` only when nothing was sent.

**Every 403 precedes every 409**: an unauthorized caller never learns the billing state. `402` is never produced by a billing endpoint.

### 17.2 Never gated, never blocked by read-only mode (M15)

The PLAN-02 allow-list scan test ([plans.md](plans.md) section 18 item 10) gets these additions: every controller under `/api/v1/admin/billing/**`, `/api/v1/platform/billing/**` and `/api/v1/dev/billing/**`, the card-setup endpoints `/api/v1/platform/onboarding/card/**`, and the billing webhook. They never call `EntitlementService.requireRoute` / `requireWrite` / `requireLimitSlot`; a billing endpoint answers `200` while the tenant is `READ_ONLY` (a test per endpoint family: the member has to be able to pay and cancel while restricted). Background billing code (section 15) never calls a gated service; BILL-01c classifies every existing background caller (`@NotPlanGated(reason)` / `@PlanGatedBackground`) and a test fails on an unclassified caller.
Authorization: [authorization-matrix.md](authorization-matrix.md#3c-subscription-billing-bill-00-contract).

### 17.3 Error catalogue

All bodies are the envelope; `ApiCodeException` shape `{"description", "data": {"code", ...}}`. Existing codes (`IDEMPOTENCY_KEY_*`, the `423`, `401`, `403`, `404`, `429`, `400` shapes) keep their documented behaviour ([errors.md](errors.md), [walk-in.md](walk-in.md)). Full list also in [errors.md](errors.md#subscription-billing-bill-00).

| HTTP | `data.code` | Extra `data` keys | When |
|---|---|---|---|
| 400 | none (field map) | `<field>: <message>` | validation of a body or query; settings rules; reports ranges |
| 400 | `CARD_AUTH_FAILED` | `retryAllowed`, `reason` | card authorization rejected (5.4, 9.5) |
| 400 | `PREVIEW_TOKEN_INVALID` | | change token missing, tampered, expired, foreign |
| 400 | `IDEMPOTENCY_KEY_REQUIRED` / `IDEMPOTENCY_KEY_INVALID` | | header missing / malformed on a money POST |
| 401 | none | | webhook: invalid signature; every route: not authenticated |
| 404 | none | | not found, another tenant's id, expired card setup token, unknown environment |
| 409 | `BILLING_NOT_CONFIGURED` | | billing disabled or no verified credentials for an action that needs Paystack |
| 409 | `CARD_REQUIRED` | | an action needs a card on file |
| 409 | `PAYMENT_PENDING` | `paymentId?` | a charge is already in flight for the subscription |
| 409 | `PAYMENT_FAILED` | `payment` | a plan-change or subscribe charge was declined (no state change) |
| 409 | `CHANGE_NOT_ALLOWED` | `reason` | `NOT_ACTIVE`, `PAYMENT_OVERDUE`, `ENDED`, `COMPLIMENTARY`, `MANUAL_MODE`, `PLAN_NOT_PURCHASABLE`, `UPGRADE_WITH_INTERVAL_CHANGE`, `TRIAL_ENDED`, `NOTHING_DUE` |
| 409 | `PENDING_CHANGE_CONFLICT` | | concurrent write on the one-pending-change slot |
| 409 | `PRICE_CHANGED` | `reason`, `newPricePesewas?`, `chargeNowPesewas?` | the preview is stale (price or plan) |
| 409 | `USE_SUBSCRIBE` | | reactivate after the subscription ended |
| 409 | `ALREADY_SUBSCRIBED` | | subscribe while a live subscription exists |
| 409 | `REFUND_EXCEEDS_REMAINING` | `remainingPesewas` | refund above the refundable remaining |
| 409 | `REFUND_NOT_ALLOWED` | `reason` | `PAYMENT_NOT_SUCCESSFUL`, `CARD_VERIFY`, `WINDOW_EXPIRED`, `WRONG_ENVIRONMENT`, `NOTHING_REFUNDABLE` |
| 409 | `SUBSCRIPTION_VERSION_CONFLICT` | `expectedVersion`, `currentVersion` | stale subscription `version` / preview token version |
| 409 | `BILLING_SETTINGS_VERSION_CONFLICT` | `expectedVersion`, `currentVersion` | stale settings `version` |
| 409 | `CREDENTIALS_VERSION_CONFLICT` | `expectedVersion`, `currentVersion` | stale credential `version` where a client sends one |
| 409 | `BILLING_MANAGED` | | PLAN-01 status bridge on a `PAYSTACK` tenant ([plans.md](plans.md) section 11) |
| 409 | `CREDENTIALS_NOT_CONFIGURED` | | `verify` for an environment without keys |
| 409 | `ACTION_NOT_ALLOWED` | `action`, `lifecycleStatus`, `accessState` | admin action illegal for the state |
| 409 | `NOT_COMPLIMENTARY` | | end of a complimentary plan that is not one |
| 409 | `IDEMPOTENCY_KEY_REUSED` | | same key, different request |
| 409 | `RECONCILIATION_IN_PROGRESS` | | manual reconciliation run while one is running |
| 409 | `FINDING_ALREADY_RESOLVED` | | resolve a resolved finding |
| 409 | `WEBHOOK_EVENT_NOT_RETRYABLE` | | retry of an event that is not `FAILED` |
| 413 | none | | webhook body > 64 KiB |
| 423 | none | | suspended tenant (tenant resolution, all tenant routes) |
| 429 | none | | throttle (`Retry-After`) |
| 503 | `PROVIDER_UNAVAILABLE` | | Paystack unreachable / circuit open BEFORE anything was sent |

Provider text is never returned: `failureCode` values are the fixed enum `DECLINED`, `INSUFFICIENT_FUNDS`, `CARD_EXPIRED`, `AUTHORIZATION_REVOKED`, `PROVIDER_ERROR`, `VERIFICATION_MISMATCH`, `UNKNOWN` (Paystack responses are summarised, never logged or forwarded, `PaystackErrorSummary` pattern).

### 17.4 Caching (S7; extends [plans.md](plans.md) section 15.1)

The entitlement cache value gains `accessState` and `billingManaged` (BILL-01c). Every event of 3.2 that changes the subscription status, the access state, the plan or the lock evicts through the generation-guarded cache AFTER COMMIT (`TenantSubscriptionChangedEvent`), so the next tenant request sees it; a failed eviction of a change that REVOKES access (restriction, cancellation, downgrade, refund-driven cancel) is retried with backoff and, if still failing, a metric and an ERROR fire and the tenant key is written with a very short TTL marker. A reader that loaded the old state cannot write it back after the eviction (per-key generation compare-and-set). A Redis failure reads the database and never grants by default; `readOnly` is never cached.

### 17.5 Audit (FR-26, BR-10, S6)

`billing_audit_log` is append-only (a trigger refuses `UPDATE` and `DELETE`, a test proves it through JDBC) and every row is written in the SAME transaction as the change. Shape `BillingAuditRow`: `occurredAt`, `actorType` (`SYSTEM`, `WEBHOOK`, `MEMBER`, `PLATFORM_ADMIN`), `actorId` and `actorEmail` (the ADMIN user or the platform admin; null for `SYSTEM` / `WEBHOOK`), `tenantId` (immutable id) and `tenantSlug`, `action`, `reference` (the Paystack reference when there is one), `reason` (the free text of an admin action), `before` / `after` (JSON objects of the changed fields only).
**`before` / `after` never contain a secret, an authorization code, a card number, a webhook body or a provider message.** `action` is a fixed upper-case code, at least: `SUBSCRIPTION_CREATED`, `TRIAL_CHARGE_SUCCEEDED`, `PAYMENT_ATTEMPTED`, `PAYMENT_SUCCEEDED`, `PAYMENT_FAILED`, `PAYMENT_FLAGGED`, `RETRY_SCHEDULED`, `GRACE_STARTED`, `ACCESS_RESTRICTED`, `ACCESS_RESTORED`, `PLAN_UPGRADED`, `DOWNGRADE_SCHEDULED`, `PLAN_CHANGE_APPLIED`, `PENDING_CHANGE_CLEARED`, `CANCELLATION_REQUESTED`, `CANCELLATION_REVERSED`, `SUBSCRIPTION_ENDED`, `RESUBSCRIBED`, `CARD_AUTHORIZED`, `CARD_REPLACED`, `REFUND_REQUESTED`, `REFUND_COMPLETED`, `REFUND_FAILED`, `COMPLIMENTARY_GRANTED`, `COMPLIMENTARY_ENDED`, `TRIAL_EXTENDED`, `ADMIN_ACTIVATED`, `ADMIN_RESTRICTED`, `ADMIN_RESTORED`, `ADMIN_PLAN_CHANGED`, `PRICE_CHANGE_NOTICED`, `PRICE_CHANGE_APPLIED`, `RECONCILIATION_FINDING_RAISED`, `RECONCILIATION_FINDING_RESOLVED`, `WEBHOOK_EVENT_RETRIED`, `SETTINGS_CHANGED` (a reference to the settings audit row), `CREDENTIALS_CHANGED`, `CREDENTIALS_VERIFIED`. The V6 tenant audit `action` CHECK is extended only if the tenant audit is also written. Ledger rows (`billing_payments`, `billing_adjustments`, documents) keep a snapshot of the tenant name and owner e-mail and are never deleted by a tenant purge.

## 18. Rate limits (S10)

Token buckets in the style of [onboarding.md](onboarding.md) section 7 (per JVM instance; burst = capacity); a throttled request is `429` with `Retry-After`. Defaults (properties `billing.rate.<name>.capacity` / `.period-seconds`):

| Limiter | Key | Capacity | Period |
|---|---|---|---|
| `card-init-ip` (public) | client IP | 10 | 600 s |
| `card-init-token` (public) | the setup token | 5 | 600 s |
| `card-verify-ip` (public) | client IP | 20 | 600 s |
| `admin-card-init` | tenant | 5 | 600 s |
| `admin-card-verify` | tenant | 10 | 600 s |
| `admin-change-preview` | tenant | 30 | 600 s |
| `admin-change` (and `subscribe`) | tenant | 6 | 600 s |
| `admin-retry` | tenant | 3 | 600 s (and at least 60 s between attempts) |
| `admin-cancel` | tenant | 10 | 600 s |
| `admin-document-resend` | tenant | 5 | 3600 s |
| `platform-refund` | platform admin | 10 | 600 s |
| `platform-action` (manual actions, complimentary) | platform admin | 30 | 600 s |
| `platform-verify-credentials` | platform admin | 5 | 600 s |
| `platform-reconcile-run` | platform admin | 2 | 600 s |

The webhook is not rate limited (the provider retries by design); its protection is the signature, the size cap and the invalid-signature counter.

## 19. Test support: the fake Paystack and the dev billing clock (S4, S8; BILL-01, BILL-RS)

No real Paystack keys exist yet (O19): everything is built and tested against a **FAKE Paystack** and a human smoke test with real test keys is the release gate.

### 19.1 The fake (BILL-01; JVM-embedded for JUnit and a standalone process for the frontend real-stack e2e)

It is reached by pointing the per-environment base URL (`paystack.url`) at it and implements: `transaction/initialize`, `transaction/verify/{reference}`, `transaction/charge_authorization`, `refund`, `transaction` (list, for reconciliation), `customer`; a **hosted fake checkout** page that completes or declines and redirects to the callback URL; scripted outcomes; a webhook emitter; and a request recorder. Response shapes are copied from the Paystack documentation fixtures and the fake's own tests prove it behaves like them.

| Scripted outcome | How it is selected | What the fake does |
|---|---|---|
| success | default card | charges, `verify` says success with the right amount and currency |
| insufficient funds | authorization minted by the "insufficient funds" fake card | `charge_authorization` answers a failed transaction |
| declined | "declined" card | failed transaction |
| timeout | "timeout" card | processes the charge, then does not answer in time (exercises `UNKNOWN_OUTCOME`, no double charge) |
| 5xx | "server error" card | HTTP 500 / 502, exercises the circuit breaker |
| verify mismatch | "wrong amount" / "wrong currency" card | `verify` reports another amount or currency than charged |
| non-reusable | "non-reusable" card | the hosted checkout yields `reusable: false` |
| delayed confirmation | "delayed" card | `pending` for N seconds, then a webhook |

Magic values are the cards chosen on the fake checkout page (the fake mints authorization codes that remember their scenario) plus a control endpoint to script the next outcomes of a reference prefix. **Webhook emitter**: fires events with a REAL HMAC-SHA512 of the exact body using the fake secret configured for the environment, with controls for duplicates, delay, reordering, drops and wrong signatures. **Recorder**: records every request (method, path, reference, amount) so tests assert idempotency: the same reference twice is ONE charge. Test credentials of the fake are documentation values, never real keys.

### 19.2 Time: the injectable `Clock` and `DevBillingController`

Tests inject a controllable `Clock`. For the real-stack e2e a dev-profile-only `DevBillingController` (precedent `DevTenantController`, enabled by `tenant.dev-endpoints.enabled`, `/api/v1/dev/**` is `denyAll` otherwise) exposes: `GET /api/v1/dev/billing/clock` (`now`, the offset, `frozen`), `POST /api/v1/dev/billing/clock/advance` (`{seconds}` or `{to}` or `{reset: true}`) and `POST /api/v1/dev/billing/run` (one billing round, synchronously, with the counts of what it did). A test asserts that the bean does NOT exist under the `prod` profile and that the routes are `denyAll` without the property.

<!-- example: dev-clock response DevClockEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "now": "2026-12-11T09:29:00Z",
    "offsetSeconds": 1209540,
    "frozen": false
  }
}
```

<!-- example: dev-clock-advance-seconds valid DevClockAdvanceRequest -->
```json
{
  "seconds": 86400
}
```

<!-- example: dev-clock-advance-to valid DevClockAdvanceRequest -->
```json
{
  "to": "2026-12-11T09:30:00Z"
}
```

<!-- example: dev-clock-reset valid DevClockAdvanceRequest -->
```json
{
  "reset": true
}
```

<!-- example: dev-clock-advance-zero invalid DevClockAdvanceRequest 400 -->
```json
{
  "seconds": 0
}
```

<!-- example: dev-billing-run-result response DevBillingRunEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "now": "2026-12-11T09:30:00Z",
    "trialCharges": 1,
    "renewals": 0,
    "retries": 0,
    "graceExpiries": 0,
    "reminders": 2,
    "reconciled": 0
  }
}
```

### 19.3 Layers every backend ticket uses (wave testing strategy)

Unit (state machine, proration and rounding, schedule maths), repository and migration tests on a real Postgres (Testcontainers as the suite does), integration with the fake, cross-tenant tests (tenant A can never see or change tenant B's billing, authorization codes or invoices), an authorization matrix per endpoint (anonymous `401`, tenant roles per the matrix, FRONT_DESK `403`, non-SUPER_ADMIN platform `403`), concurrency tests (two webhooks, a retry and a user action on one subscription: exactly one charge and one invoice), contract tests that parse every example of this document, mutants per behaviour, and the FULL 4-batch suite through `run.sh` at the final head.

## 20. Pending owner decisions and decisions made by this contract

### 20.1 O2: recurring charges are CARD ONLY (FINAL, owner answer 2026-11-28)

Recurring plan fees are charged automatically to a reusable CARD authorization only; there is no automatic mobile-money or bank charging (Paystack `charge_authorization` works for reusable card authorizations, M17). `allowedChannels` and `recurringChannels` default to `["card"]` and the card step accepts only a reusable `card` authorization (`CARD_AUTH_FAILED` reason `NOT_CARD` otherwise). A **pay-each-period payment-link flow for card-less shops is explicitly a LATER addition and OUT OF SCOPE**. The state model already accommodates it without change to sections 3 and 8: a payment row with a reference in `PENDING` models "awaiting the member", and grace, reminders, read-only and restore would stay as they are; the later addition would add a new `billingMode` value and a pay-link URL on the pending payment and in `nextBilling` (an additive contract revision). If Paystack later offers reusable mobile-money authorizations, adding `mobile_money` to both channel settings is a settings change.

### 20.2 O18: PDF library (FINAL: YES, owner answer 2026-11-28)

A PDF library is added; BILL-05 picks OpenPDF or Apache PDFBox (programmatic layout, no HTML-to-PDF renderer that fetches URLs) and justifies the choice in its PR. The PDF endpoints, headers and behaviour of 10.4 are final: rendered on demand from the immutable invoice row, nothing stored, deterministic. `attachPdfToEmail` stays a setting, default `false`.

### 20.3 Decisions made by this contract (the board or the PO brief was silent or ambiguous; change here first)

1. `cardRequired`, `phase` and the token keys are ABSENT (not `false`) when the card step does not apply, so every existing onboarding example stays valid (5.1). The token is returned only by `verify`, never by `status`.
2. Status stays `PROVISIONING` while the card is due (with `phase AWAITING_CARD`), so an old wizard that ignores `phase` keeps polling instead of showing the OTP step again (5.2).
3. References use hyphens only (Paystack's reference alphabet); `sub-card-<t|l>-...` replaces the board's `sub_card_<random>` (section 1).
4. Classification: price rank by the catalog `monthlyPricePesewas`; equal price is a downgrade (deferred, never charged); an upgrade combined with an interval change is refused (two steps) (6.3).
5. The amount shown by the preview is the amount charged (the token fixes `changeAt` and the amount); a change at the last second that rounds to 0 applies at once without a charge (6.3).
6. A price change of a plan creates a notice for every affected subscription, including price DECREASES (uniform rule, D3); a second edit replaces the notice and restarts the clock (6.4).
7. `EXPIRED` is produced only by a fixed-term admin `ACTIVATE` with `until`; every ended-by-cancellation subscription is `CANCELED`. Both map to `tenants.subscription_status = CANCELED` (3, 3.4).
8. A refund with `cancelSubscription: true` schedules non-renewal; it does not end access (7.10). `RESTRICT` sets a `manualRestriction` that payments do not lift (7.11).
9. A Paystack `401` / `403` with our own key is a platform fault: the environment becomes `FAILED`, billing jobs skip it, no member is charged a failure or put in grace (4.4).
10. Settings added to the board list so that D2, O2, O18 and O29 are real settings: `graceEndingReminderDaysBefore`, `recurringChannels`, `webhookIpAllowlist`, `attachPdfToEmail`, `emailEnabled` (one flag per notification kind).
11. Retries clamp to `graceEndsAt`; with `graceDays = 0` access is restricted at the first failure and no retries run (4.1, 8.2).
12. `callback-verify` is a `GET` with a server-side effect (the board's contract); it is made safe by the custom token header and by idempotent replay of the same reference for 15 minutes (5.4).
13. `DELETE .../complimentary` carries a JSON body (reason and `expectedVersion`) (11.4).
14. `MANUAL` mode tenants subscribe through `POST /subscribe` after adding a card (7.5, 7.12).
15. Verification holds (`CARD_VERIFY`) are not listed in the member billing history and cannot be refunded by an admin (the system refunds them) (9.7, 7.10).

### 20.4 O9: tax treatment (STILL PENDING; kept: prices are tax-inclusive with an optional configurable tax line, empty until supplied)

No tax is assumed (D4). **Either answer is a settings change:** (a) tax already included in the displayed prices: set `taxRateBps`, keep `taxInclusive = true`: charges do not change, documents show the extracted net and tax; (b) tax is added on top: set `taxRateBps` and `taxInclusive = false`: every charge becomes net + tax (6.6), and the pricing page copy must say "plus tax" (a frontend copy decision, not an API change). The business name, TIN, address and footer are the owner's input (empty until supplied); sequential numbering and TIN requirements of the tax authority are the owner's tax advice. The illustrative 15% in examples is NOT a statement about any tax.

### 20.5 Other owner questions: the assumed defaults and where they live

| Q | Assumed default | Section / setting |
|---|---|---|
| O1 | FINAL: GHS 1.00 verification charge, refunded immediately, no invoice, amount configurable | `cardVerificationAmountPesewas`, 5.4 |
| O3 | card step after OTP, before provisioning | 5 |
| O4 | existing tenants stay `MANUAL` until a card or a complimentary plan; `NO_CARD` policy at trial end | 7.12 |
| O5 | own scheduler + `charge_authorization` (not Paystack Subscriptions) | 7, 15 |
| O6 | monthly or annual in self-service; signup starts monthly; interval switch at the next billing date | 6.3 |
| O7 | no refund of an earlier prorated charge on a later downgrade | 6.3, 7.7 |
| O8 | price change notice 30 days (0 allowed) | `priceChangeNoticeDays`, 6.4 |
| O10 | invoice AND receipt per successful charge, credit note per refund, no unpaid invoice | 10.1 |
| O11 | refund money only; partial within 90 days; one SUPER_ADMIN with a reason | 7.10, `refundMaxDays` |
| O12 | disputes: alert and audit only | 12.5 |
| O13 | complimentary: billing paused, no card needed, excluded from revenue; resumes or `NO_CARD` | 7.9 |
| O14 | trial reminders 3 and 1 day + on the day, renewal 3 days, grace at each retry + 1 day before the end | section 4.1, 16 |
| O15 | billing restriction = `READ_ONLY` on paid features, never the tenant `SUSPENDED` lock | 3, 8.3 |
| O16 | owner e-mail + banners for ADMIN and FRONT_DESK (read); money actions ADMIN only | 9, 16 |
| O17 | one active environment, test and live records never mix; LIVE needs a new card authorization; separate encryption key recommended | 1, 4.4 |
| O19 | no real keys: fake Paystack and a human smoke test; `billingEnabled` stays false until then | 19 |
| O20 | billing records are kept with a snapshot (retention period and erasure requests are the owner's call) | 17.5, 10.3 |
| O21 | revenue is gross charged, net of refunds; fees shown separately | 14.2 |
| O22 | PLAN-01 bridge kept for `MANUAL`, `409 BILLING_MANAGED` for `PAYSTACK` | 3.3, 7.12 |
| O23 | PLAN-06 transaction-fee split stays out of scope | 6.4 |
| O24 | a plan-change charge failure leaves the plan unchanged, no grace | 7.6, 8.8 |
| O25 | cancel during grace: non-renewal, ends at grace end | 7.4 |
| O26 | reactivation after the end is a new subscription, charged now, no second trial | 7.5 |
| O27 | annual proration uses the real seconds of the period (leap years included) | 6.3 |
| O28 | the period continues from the original due date after a late payment | 6.2 |
| O29 | webhook IP allow-list off by default | 12.6 |
| O30 | copy "Card required, charged after the trial" is the frontend's constant, confirmed by the owner in the FE PR | [onboarding.md](onboarding.md) section 3b |

## 21. Requirements for BILL-01..07 implementations

Security signs off each billing ticket against this list (the board's S1-S10), the same way section 18 of [plans.md](plans.md) works for PLAN-01 / PLAN-02. A ticket that touches payments, webhooks, credentials, auth, tenancy, caching or scheduled jobs is `Security review: yes`.

1. **S1 Card data.** Only Paystack authorization tokens; never a PAN or CVV; the card never touches our servers (hosted checkout only). `authorization_code` is encrypted at rest and NEVER returned by an API, logged, audited, or placed in an event payload that leaves the process; only brand, last4, expiry month and year, and bank are shown. A test scans every response example and every serialised DTO for `authorization_code`, `authorization`, `signature`.
2. **S2 Credentials.** Write-only, AES-GCM with a versioned key prefix and AAD (row, field), startup refusal in a non-dev profile when the key is the dev default or shorter than 32 characters, never in logs, responses, audit JSON, error messages or OpenAPI examples; `PlatformGate` / SUPER_ADMIN only; tenant tokens never accepted; the shop's own gateway config is never read or written by billing (a static test on package dependencies: `platform.billing..` does not depend on `TenantPaymentGatewayProvider` / `PaymentGatewayConfig*`).
3. **S3 Webhook.** Section 12: exact raw-body HMAC-SHA512 with the secret of the PATH environment, constant-time compare, 64 KiB cap, invalid signature = non-2xx with only a counter, never log bodies, no cookies, no CORS, no tenant resolution; every state change re-verifies with Paystack and checks amount, currency, reference prefix, environment and customer against the local row (BR-03).
4. **S4 Idempotency, no double charge.** A deterministic unique reference per (subscription, period or change sequence, attempt), a unique constraint behind it, the payment row inserted BEFORE the Paystack call, an unknown outcome resolved by `verify` and never by charging again; at most one payment in flight per subscription.
5. **S5 Tenancy.** Billing rows are master data keyed by the immutable tenant id (never the slug); tenant endpoints take the tenant only from `TenantContext`; ADMIN only for money actions (FRONT_DESK reads the banner subset only); every payment, document and refund id is checked against the caller (foreign = missing = identical `404`); the card setup token is bound to the signup tenant id.
6. **S6 Authz and audit.** Every money or access-changing action is audited (actor, role, reason, before / after, reference) in the SAME transaction; the audit log, ledger adjustments and documents are append-only through database triggers and a test proves `UPDATE` / `DELETE` is refused; `billing_payments` only moves forward and never changes amount, currency, reference, tenant or purpose.
7. **S7 Caching.** Section 17.4: generation-guarded, after-commit eviction for every change that grants or revokes access, retried when revoking, Redis down reads the DB and never grants.
8. **S8 Scheduled jobs.** Section 15: PLATFORM scope, bounded batches, per-row transactions with row locks, survive a failing round, no tenant context leakage, no gated service calls, leader lock, metrics and alerts for stuck rounds.
9. **S9 Input and output.** Money in integer pesewas (`long` / `BigDecimal`, HALF_UP, no floats), currency fixed `GHS`, free text (reasons, notes, business identity) plain text per [plans.md](plans.md) section 1 with bounded lengths, CSV cells formula-neutralised, PDF text escaped, no external resource loaded.
10. **S10 Rate limits.** Section 18; the public card-setup token is single-use for a success, short-lived (<= 30 minutes), bound to the signup tenant id.
11. **Never gated.** Section 17.2: the allow-list scan test is extended in BILL-01c, a billing endpoint answers `200` while `READ_ONLY`, no billing code path calls a gated service.
12. **Check order and codes.** Section 17.1 / 17.3; billing never answers `402`.
13. **Doc tests.** `BillingDocExamples` (the tag grammar of [onboarding.md](onboarding.md)), `BillingDocSchemaTest` (every example vs `billing.schema.json`; invalid ones fail it; `billing.schema.json` is a valid draft 2020-12 schema), `BillingDocContractTest` (every response example equals what the live server returns for the named scenario, money recomputed by the server function, every error code of 17.3 has an example) are added with the first implementing ticket and extended by each later one. They do not read `plans.md` tables (`PlanFeatureTest`, `PlanPricingTest` own those) and must not add rows that match their patterns.
14. **Existing docs tests stay green.** The `platform-tenants.md` and `onboarding.md` examples keep parsing into the CURRENT DTOs until the implementing ticket changes the DTOs and the examples in the same PR (the new tenant keys: BILL-01b; the onboarding keys: BILL-02). `authorization-matrix.md` section 3 is the reflection-checked table of REAL controllers; the billing routes are documented in section 3c and are added to the pinned table by the ticket that builds each controller (`AuthorizationMatrixStructureTest`).
15. **Stability.** Frontend and QA mark this document "stable" in a PR comment before the BILL-FE-* tickets start; after that a change to a shape is a new contract PR first.

### 21.1 The docs checker

`.keep/bill00-keep/check_examples.py <backend docs/api> <fixtures out>` (run with `python3 -I`; needs the `jsonschema` package): validates `billing.schema.json` as draft 2020-12; parses every `<!-- example -->` and `<!-- fragment -->` tag (tag count = parsed count, names unique, status present exactly for `invalid` / `response`, success descriptions `null`); validates valid / response examples against their `$defs` entry and requires invalid ones to fail (or to be listed with their server-side rule); recomputes every money figure (the proration, period, tax, credit note, retry schedule, price change and dedupe tables of sections 6, 8 and 12; every `previewToken`-bound `chargeNowPesewas`; document and payment arithmetic; refund remaining; the golden dataset and the report example; the HMAC of the worked webhook signature); checks the state invariants of 3.1 on every example that carries a `state`, `serverTime`-relative values (`daysLeft`), reference formats against the environment, that no secret-looking or authorization key appears in a response, that every error code of 17.3 has an example, and that every endpoint of section 2 is mentioned. It writes `billing-docs-examples.json` (the frontend fixtures, by name).

## 22. Traceability to the PRD

| PRD | Where |
|---|---|
| FR-01, FR-03 | 7.6, 9.6 (upgrade while signed in; plan gates and the upgrade modal are [plans.md](plans.md) section 14, never shown by billing endpoints) |
| FR-02, 4.1 | [plans.md](plans.md) sections 13-15; 3.4 |
| FR-04, FR-07 | 9 |
| FR-05, 4.4 | 3 |
| FR-06 | 5, 7.1, 7.2 |
| FR-08 | 4.4, 4.5, 12.2 |
| FR-09 | 5.5, 7.1, 7.2, 16 |
| FR-10, BR-07 | 6.3, 7.6 |
| FR-11, BR-06 | 7.7 |
| FR-12 | 7.7 |
| FR-13, FR-14 | 8 |
| FR-15, BR-03, 4.3 | 12, 13 |
| FR-16, BR-04, 4.5 | 10 |
| FR-17, FR-18 | 9.7, 9.8 |
| FR-19, FR-20, BR-05 | 7.3, 7.4 |
| FR-21 | 16 |
| FR-22, BR-10 | 11, 7.9-7.11 |
| FR-23, FR-24 | [plans.md](plans.md) (plan configuration); price changes 6.4 |
| FR-25 | 14 |
| FR-26 | 17.5 |
| BR-01, BR-02 | 3, 3.4 |
| BR-08 | 6.7, 10, 17.5 |
| BR-09 | 6.4, 7.8 |
| 4.2 | 21 (S1-S3), 12 |
| 4.6 | 4 |
