# Plans, entitlements and subscription API (PLAN-03)

Status: **contract, docs only.** Owner-approved plan: `.keep/plan-keep/PLAN.md`, board section "Paywall wave 2026-11-27"
(owner answers O1-O5). Nothing in this document exists in the code yet: PLAN-01 builds the plan catalog, the platform
plans API, the tenant plan action and the public list; PLAN-02 builds enforcement (the 402 bodies), the subscription
endpoint and the trial on sign-up. Both implement this document exactly; a deviation is a change to this document first.
Frontend: PLAN-04 (platform Plans page) and PLAN-05 (pricing page, paywall UX) build against the examples below.

| Artifact | Path |
|---|---|
| This document | `backend/docs/api/plans.md` |
| JSON Schema (draft 2020-12) for every request and response body | `backend/docs/api/plans.schema.json` |
| Plan fields on the tenant summary | [platform-tenants.md](platform-tenants.md#plan-and-subscription-fields-plan-03) |
| Signup no longer chooses a plan | [onboarding.md](onboarding.md) section 3 |
| 402 / 409 codes in the error catalogue | [errors.md](errors.md#plans-and-subscription-plan-03) |
| Routes and roles | [authorization-matrix.md](authorization-matrix.md#3b-plans-and-subscription-plan-03-contract) |
| Contract tests (to be written) | PLAN-01 `PlansDocContractTest`, PLAN-02 `SubscriptionDocContractTest` (they parse every example below) |

Every example below is tagged `<!-- example: <name> <kind> <Schema> [status] -->`, with the grammar of
[onboarding.md](onboarding.md): `valid` request examples must validate against `plans.schema.json` and be accepted by
the server, `invalid` request examples must fail the schema and be rejected with the stated status, and `response`
examples must validate against the named `$defs` entry and equal what the server returns for the named scenario
(description, data keys, and data values where deterministic). The frontend copies the examples into its fixtures
(`src/lib/platform/plans/fixtures/`, `src/lib/plans/fixtures/`, `src/lib/subscription/fixtures/`) under the same names.

## 1. Conventions

- JSON in, JSON out, camelCase. Every body, success or error, is the envelope `{ "description": string|null, "data": T|null }`.
  Success bodies have `description: null`. Errors carry display text in `description`; branch on the HTTP status and,
  where stated, on `data.code`, never on `description`.
- **Money** is integer pesewas (GHS 1 = 100 pesewas), never a float, never a string. `currency` is always `"GHS"`.
  Fees are integer basis points (`150` = 1.5%). The client formats; it does no money arithmetic (section 3.2).
- **Timestamps** of this API (`updatedAt`, `trialEndsAt`, `occurredAt`) are instants in UTC ending in `Z`, with a
  variable-length fraction that may be absent (`2026-12-11T09:30:00Z`, `2026-11-27T12:00:00.5Z`). The tenant summary's
  existing `createdAt` / `updatedAt` stay `LocalDateTime` strings without offset (platform-tenants.md); do not mix them.
- **Read-only fields are ignored on write**, never rejected: `id`, `code` (on update, see 6), `currency`,
  `annualPricePesewas`, `annualMonthlyEquivalentPesewas`, `annualSavingPesewas`, `updatedAt`, `updatedBy`, `version`
  (except as the optimistic-lock input where a request names it), `tenantCounts`, `entitlements` (on plan create and
  update; entitlements change only through the matrix). Unknown properties are ignored too (Jackson default). The schema
  marks request bodies `additionalProperties: false`, so do not send them.
- Text fields are plain text: any character except control characters (so single line), trimmed by the server, a blank
  optional text stored as `null`. Clients render them as text, never as HTML or Markdown.
- Bean-validation and rule failures are the existing 400 shape `{"description": "Validation failed", "data": {"<field path>": "<message>"}}`.
  Field paths are the JSON property names; inside the matrix they are indexed: `plans[0].entitlements[1].limitValue`,
  `plans[1].plan.trialDays`. Messages are display text.

## 2. Feature catalog

The code defines which features exist (`PlanFeature` enum, owner decision); everything else is data. The API always
returns the catalog in this order (the enum declaration order, grouped), and every plan's `entitlements` list has one
entry per catalog key in the same order. There are **17 keys** (owner O3: `MAX_CUSTOM_DOMAINS` dropped). Clients render
rows and groups from the `features` array of the response, never from a hard-coded list; `group` is display text and
groups appear in the order of their first feature.

| `key` | `kind` | `label` | `group` | What it gates (PLAN-02 AC2) |
|---|---|---|---|---|
| `CUSTOM_DOMAIN` | BOOLEAN | Custom domain | Storefront | `PUT /api/v1/admin/custom-domain` and `POST .../verify` (GET and DELETE stay open). Boolean only, there is no domain-count limit |
| `THEME_ADVANCED` | BOOLEAN | Advanced theme customisation | Storefront | `PUT /api/v1/theme` when it adds or changes `rich-text`, `story` or `banner` sections, footer `linkGroups`, colour/typography overrides beyond the catalog theme, or a background image (O2), compared with the stored document. Storefront `GET` never gated |
| `MAX_PRODUCTS` | LIMIT | Products | Catalog | product create (`POST /api/v1/product/add-product`, including a variant created into a family) |
| `PRODUCT_FAMILIES` | BOOLEAN | Product families and pack sizes | Catalog | `ProductFamilyController`, and `familyId` / `uomCode` / `conversionFactor` on product create and update |
| `WALK_IN_POS` | BOOLEAN | Walk-in point of sale | Orders | `WalkInOrderController` (every route) |
| `RETURNS_REFUNDS` | BOOLEAN | Returns and refunds | Orders | `OrderController` return, return-received, refund-acknowledgement |
| `SHOP_WIDE_DISCOUNTS` | BOOLEAN | Shop-wide discounts | Marketing | `ShopWideDiscountController` writes |
| `MAX_ACTIVE_COUPONS` | LIMIT | Active coupons | Marketing | coupon create, and a coupon update that re-activates a coupon |
| `INVENTORY_MANAGEMENT` | BOOLEAN | Inventory adjustments and history | Inventory | `InventoryManagementController` `POST /adjustments` and `GET /history` only (receipts, summary and stock-by-location stay core: receipts are the only way stock enters) |
| `STOCK_TRANSFERS` | BOOLEAN | Stock transfers between locations | Inventory | `StockTransferController` |
| `STOCK_CONVERSION` | BOOLEAN | Stock conversion | Inventory | `StockConversionController` |
| `MAX_LOCATIONS` | LIMIT | Storage locations | Inventory | storage location create |
| `SALES_REPORTS` | BOOLEAN | Sales reports | Finance and reports | `SalesReportController` except `items-sold/{productId}` (used by the product detail page) |
| `TAX_CONFIG` | BOOLEAN | Tax configuration | Finance and reports | `TaxController` add, update, delete (reads stay open; checkout keeps applying existing active taxes) |
| `ACCOUNTING` | BOOLEAN | Accounting and bookkeeping | Finance and reports | `AccountingController`, `BookkeepingController` (ledger posting from orders keeps running) |
| `MAX_STAFF` | LIMIT | Front-desk staff accounts | Team and security | front-desk user create, and a `PATCH` that re-activates a front-desk user |
| `LOGIN_OTP` | BOOLEAN | Login one-time codes | Team and security | `PATCH /api/v1/admin/feature-flags/LOGIN_OTP` when it turns an audience **on** (turning off is always allowed, see section 9) |

Never gated (core): platform, onboarding, auth, storefront, cart, checkout, payment, webhook, notification and metrics
endpoints, orders other than the three return routes, products other than the limit, categories, coupons other than the
limit, the subscription endpoint itself. Labels and groups are code-defined (they change with a release, not through the
API).

Catalog entry (`Feature`): `key` (string), `kind` (`BOOLEAN` | `LIMIT`), `label` (1..60), `group` (1..40). A client that
meets a `kind` it does not know must drop the row (and log), not render it.

## 3. Plan

### 3.1 Fields

`P` = also in the public shape (public list and subscription). The platform shape adds `version`, `updatedBy` and
`tenantCounts`; the public shape omits exactly those three.

| Field | Type | Rules | P | Writable |
|---|---|---|---|---|
| `id` | integer | server id | yes | no |
| `code` | string | `^[A-Z][A-Z0-9_]{1,31}$`, unique (`409 PLAN_CODE_TAKEN`), **immutable after create** | yes | on create only |
| `name` | string | 1..40, plain text | yes | yes |
| `tagline` | string \| null | <= 40 (card eyebrow, e.g. `FOR YOUR FIRST SALES`; shown as stored) | yes | yes |
| `description` | string \| null | <= 280 | yes | yes |
| `monthlyPricePesewas` | integer | 0..100,000,000 (GHS 0 to 1,000,000) | yes | yes |
| `annualDiscountPercent` | integer | 0..100 (owner O5; seed 20) | yes | yes |
| `annualPricePesewas` | integer | **derived**, read-only (3.2) | yes | ignored |
| `annualMonthlyEquivalentPesewas` | integer | **derived**, read-only (3.2) | yes | ignored |
| `annualSavingPesewas` | integer | **derived**, read-only (3.2) | yes | ignored |
| `currency` | `"GHS"` | constant | yes | ignored |
| `trialDays` | integer | 0..365; 0 = no trial (new tenants start with `trialEndsAt` = provisioning time, i.e. already read-only until set `ACTIVE`) | yes | yes |
| `transactionFeeBps` | integer | 0..10,000; display-only until PLAN-06 collects it | yes | yes |
| `isPublic` | boolean | listed on the public pricing page (when also `active`) | yes | yes |
| `isDefaultForSignup` | boolean | exactly one plan is the default; every new tenant gets it (O1). Rules in section 7 | yes | yes |
| `highlighted` | boolean | card styling only | yes | yes |
| `badgeLabel` | string \| null | <= 40; badge shown only when set | yes | yes |
| `bulletsHeading` | string \| null | <= 80 | yes | yes |
| `marketingBullets` | string[] | 0..12 items, each 1..120, ordered; free text, separate from entitlements | yes | yes |
| `sortOrder` | integer | 0..1000; lists sort by `sortOrder`, then `id` | yes | yes |
| `active` | boolean | inactive plans are hidden from the public list and cannot be assigned without `allowInactive`; tenants already on them keep working | yes | yes |
| `updatedAt` | timestamp | last write to the plan or its entitlements | yes | no |
| `entitlements` | `Entitlement[]` | one per catalog key, catalog order (3.3) | yes | matrix only |
| `version` | integer | optimistic lock, starts at 0, +1 on every write that changes the plan or its entitlements | no | as lock input |
| `updatedBy` | string \| null | e-mail of the platform admin of the last write; `null` for rows written by the migration | no | no |
| `tenantCounts` | object | tenants on this plan per subscription status; always all four keys `TRIALING`, `ACTIVE`, `PAST_DUE`, `CANCELED` | no | no |

### 3.2 Derived annual prices (owner O5)

Computed by ONE server function, in integer / `BigDecimal` arithmetic, never floating point, and returned on every plan
response (platform and public):

```
annualPricePesewas             = round_half_up(monthlyPricePesewas * 12 * (100 - annualDiscountPercent) / 100)
annualMonthlyEquivalentPesewas = round_half_up(annualPricePesewas / 12)
annualSavingPesewas            = monthlyPricePesewas * 12 - annualPricePesewas
```

`round_half_up` rounds to whole pesewas, a fraction of exactly .5 away from zero (`RoundingMode.HALF_UP`; all values are
>= 0). The pricing page shows `annualMonthlyEquivalentPesewas` "/ month", "Billed GHS `annualPricePesewas` yearly" and
"Save GHS `annualSavingPesewas` / year"; `annualMonthlyEquivalentPesewas * 12` may differ from `annualPricePesewas` by a few
pesewas, the billed amount is `annualPricePesewas`.

Worked values (the PLAN-01 unit test pins every row):

| monthly | % | exact annual | `annualPricePesewas` | exact / 12 | `annualMonthlyEquivalentPesewas` | `annualSavingPesewas` | note |
|---|---|---|---|---|---|---|---|
| 14900 | 20 | 143040 | **143040** | 11920 | **11920** | **35760** | seed Starter (GHS 149 -> 1,430.40 / yr) |
| 39900 | 20 | 383040 | **383040** | 31920 | **31920** | **95760** | seed Growth (GHS 399 -> 3,830.40 / yr) |
| 99900 | 20 | 959040 | **959040** | 79920 | **79920** | **239760** | seed Scale (GHS 999 -> 9,590.40 / yr) |
| 44900 | 15 | 457980 | 457980 | 38165 | 38165 | 80820 | example `plan-updated` |
| 14901 | 20 | 143049.6 | 143050 | 11920.83... | 11921 | 35762 | annual rounds up |
| 14999 | 15 | 152989.8 | 152990 | 12749.16... | 12749 | 26998 | |
| 12345 | 17 | 122956.2 | 122956 | 10246.33... | 10246 | 25184 | annual rounds down |
| 14902 | 23 | 137694.48 | 137694 | **11474.5** | **11475** | 41130 | per-month lands on .5: HALF_UP gives 11475 (HALF_EVEN would give 11474) |
| 1 | 50 | 6 | 6 | **0.5** | **1** | 6 | smallest .5 case |
| 14900 | 0 | 178800 | 178800 | 14900 | 14900 | 0 | no discount |
| 14900 | 100 | 0 | 0 | 0 | 0 | 178800 | free annual |
| 0 | 20 | 0 | 0 | 0 | 0 | 0 | free plan |

The annual price itself can never land on exactly .5 pesewa: `monthly * 12 * (100 - p) / 100 = monthly * 3 * (100 - p) / 25`,
so its fraction is a multiple of 1/25 (0.04) and never 0.5. Only the per-month equivalent can hit .5 (rows 14902/23 and 1/50).

### 3.3 Entitlements

`Entitlement` = `{ "featureKey", "enabled", "limitValue" }`.

- **BOOLEAN**: `enabled` is the switch; `limitValue` is always `null`. Writing a non-null `limitValue` for a BOOLEAN key
  is `400` (`... .limitValue`: `must be null for a BOOLEAN feature`).
- **LIMIT**: `limitValue` is the cap: an integer `>= 0`, `null` = **unlimited**, `0` = none allowed. `enabled` is derived
  (`limitValue == null || limitValue > 0`) for display; it is ignored on write. A negative value is `400`.
- A catalog key with no stored row reads as off (BOOLEAN `enabled: false`) or `0` (LIMIT). A stored key that is not in
  the catalog is ignored on read (and logged), never returned.
- A new plan (POST) starts with every BOOLEAN off and every LIMIT `0`; entitlements change only through the matrix (8).
- An unknown `featureKey` in a write is `400` (`... .featureKey`: `unknown feature`). The same key twice in one plan item
  is `400`.

## 4. Endpoints at a glance

| Endpoint | Auth | Success | Purpose |
|---|---|---|---|
| `GET /api/v1/platform/plans` | SUPER_ADMIN | 200 | every plan (incl. inactive and non-public) with entitlements, tenant counts, and the catalog |
| `POST /api/v1/platform/plans` | SUPER_ADMIN | 201 | create a plan |
| `PUT /api/v1/platform/plans/{id}` | SUPER_ADMIN | 200 | replace a plan's fields (not its entitlements) |
| `PUT /api/v1/platform/plans/matrix` | SUPER_ADMIN | 200 | entitlements (and optionally fields) of several plans in ONE transaction |
| `GET /api/v1/platform/plans/audit` | SUPER_ADMIN | 200 | plan audit log, paged, newest first |
| `PUT /api/v1/platform/tenants/{slug}/plan` | SUPER_ADMIN | 200 | set one tenant's plan, trial end or subscription status |
| `GET /api/v1/platform/onboarding/plans` | public | 200 | public active plans for the pricing page and the signup wizard |
| `GET /api/v1/admin/subscription` | tenant ADMIN, FRONT_DESK | 200 | the caller's tenant: plan, status, trial, read-only flag, entitlements, usage |

There is **no delete** (deactivate with `active: false`), no `GET /platform/plans/{id}` (use the list), and no
per-plan entitlements endpoint (use the matrix).

Platform endpoints (`/api/v1/platform/plans/**`, `/api/v1/platform/tenants/{slug}/plan`) follow platform-tenants.md:
`platformAccessToken` cookie, platform CORS allow-list (any other `Origin` is `403` from CORS), tenant cookies and
tenant JWTs of any role never authenticate (`401`), an authenticated non-SUPER_ADMIN gets `403`.

<!-- example: platform-unauthenticated response ErrorEnvelope 401 -->
```json
{ "description": "Full authentication is required to access this resource", "data": null }
```

<!-- example: platform-forbidden response ErrorEnvelope 403 -->
```json
{ "description": "Access Denied", "data": null }
```

## 5. `GET /api/v1/platform/plans`

No query parameters. `data.plans`: every plan in `sortOrder`, then `id`, in the platform shape. `data.features`: the
catalog. Read-only transaction, no audit row.

<!-- example: platform-plans-list response PlanCatalogEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "plans": [
      {
        "id": 1,
        "code": "STARTER",
        "name": "Starter",
        "tagline": "FOR YOUR FIRST SALES",
        "description": "Everything you need to launch your first online store.",
        "monthlyPricePesewas": 14900,
        "annualDiscountPercent": 20,
        "annualPricePesewas": 143040,
        "annualMonthlyEquivalentPesewas": 11920,
        "annualSavingPesewas": 35760,
        "currency": "GHS",
        "trialDays": 14,
        "transactionFeeBps": 150,
        "isPublic": true,
        "isDefaultForSignup": true,
        "highlighted": false,
        "badgeLabel": null,
        "bulletsHeading": "Your launch essentials",
        "marketingBullets": [
          "Free platform subdomain (yourstore.shopfi.store)",
          "Up to 50 products",
          "Integrated online checkout & payment processing",
          "Up to 3 active coupon codes",
          "Standard storefront templates"
        ],
        "sortOrder": 10,
        "active": true,
        "updatedAt": "2026-11-27T09:40:00Z",
        "entitlements": [
          {"featureKey": "CUSTOM_DOMAIN", "enabled": false, "limitValue": null},
          {"featureKey": "THEME_ADVANCED", "enabled": false, "limitValue": null},
          {"featureKey": "MAX_PRODUCTS", "enabled": true, "limitValue": 50},
          {"featureKey": "PRODUCT_FAMILIES", "enabled": false, "limitValue": null},
          {"featureKey": "WALK_IN_POS", "enabled": false, "limitValue": null},
          {"featureKey": "RETURNS_REFUNDS", "enabled": false, "limitValue": null},
          {"featureKey": "SHOP_WIDE_DISCOUNTS", "enabled": false, "limitValue": null},
          {"featureKey": "MAX_ACTIVE_COUPONS", "enabled": true, "limitValue": 3},
          {"featureKey": "INVENTORY_MANAGEMENT", "enabled": false, "limitValue": null},
          {"featureKey": "STOCK_TRANSFERS", "enabled": false, "limitValue": null},
          {"featureKey": "STOCK_CONVERSION", "enabled": false, "limitValue": null},
          {"featureKey": "MAX_LOCATIONS", "enabled": true, "limitValue": 1},
          {"featureKey": "SALES_REPORTS", "enabled": false, "limitValue": null},
          {"featureKey": "TAX_CONFIG", "enabled": false, "limitValue": null},
          {"featureKey": "ACCOUNTING", "enabled": false, "limitValue": null},
          {"featureKey": "MAX_STAFF", "enabled": false, "limitValue": 0},
          {"featureKey": "LOGIN_OTP", "enabled": false, "limitValue": null}
        ],
        "version": 7,
        "updatedBy": "root@platform.example",
        "tenantCounts": {"TRIALING": 9, "ACTIVE": 0, "PAST_DUE": 0, "CANCELED": 1}
      },
      {
        "id": 2,
        "code": "GROWTH",
        "name": "Growth",
        "tagline": "FOR ESTABLISHED SELLERS",
        "description": "Full brand ownership and tools to maximize conversions.",
        "monthlyPricePesewas": 39900,
        "annualDiscountPercent": 20,
        "annualPricePesewas": 383040,
        "annualMonthlyEquivalentPesewas": 31920,
        "annualSavingPesewas": 95760,
        "currency": "GHS",
        "trialDays": 14,
        "transactionFeeBps": 100,
        "isPublic": true,
        "isDefaultForSignup": false,
        "highlighted": true,
        "badgeLabel": "Most popular",
        "bulletsHeading": "Everything in Starter, plus:",
        "marketingBullets": [
          "Custom domain connection (yourbrand.com)",
          "Up to 1,000 products & inventory management",
          "Full storefront customisation (theme & layout editor)",
          "Shop-wide discounts, returns & refunds",
          "Tax configuration & sales reports",
          "Walk-in point of sale",
          "Lower platform transaction fees"
        ],
        "sortOrder": 20,
        "active": true,
        "updatedAt": "2026-11-27T09:45:00Z",
        "entitlements": [
          {"featureKey": "CUSTOM_DOMAIN", "enabled": true, "limitValue": null},
          {"featureKey": "THEME_ADVANCED", "enabled": true, "limitValue": null},
          {"featureKey": "MAX_PRODUCTS", "enabled": true, "limitValue": 1000},
          {"featureKey": "PRODUCT_FAMILIES", "enabled": true, "limitValue": null},
          {"featureKey": "WALK_IN_POS", "enabled": true, "limitValue": null},
          {"featureKey": "RETURNS_REFUNDS", "enabled": true, "limitValue": null},
          {"featureKey": "SHOP_WIDE_DISCOUNTS", "enabled": true, "limitValue": null},
          {"featureKey": "MAX_ACTIVE_COUPONS", "enabled": true, "limitValue": null},
          {"featureKey": "INVENTORY_MANAGEMENT", "enabled": true, "limitValue": null},
          {"featureKey": "STOCK_TRANSFERS", "enabled": false, "limitValue": null},
          {"featureKey": "STOCK_CONVERSION", "enabled": false, "limitValue": null},
          {"featureKey": "MAX_LOCATIONS", "enabled": true, "limitValue": 1},
          {"featureKey": "SALES_REPORTS", "enabled": true, "limitValue": null},
          {"featureKey": "TAX_CONFIG", "enabled": true, "limitValue": null},
          {"featureKey": "ACCOUNTING", "enabled": false, "limitValue": null},
          {"featureKey": "MAX_STAFF", "enabled": true, "limitValue": 3},
          {"featureKey": "LOGIN_OTP", "enabled": true, "limitValue": null}
        ],
        "version": 3,
        "updatedBy": "root@platform.example",
        "tenantCounts": {"TRIALING": 2, "ACTIVE": 10, "PAST_DUE": 0, "CANCELED": 0}
      },
      {
        "id": 3,
        "code": "SCALE",
        "name": "Scale",
        "tagline": "FOR HIGH-VOLUME BRANDS",
        "description": "Advanced operations, team access, and lowest fees.",
        "monthlyPricePesewas": 99900,
        "annualDiscountPercent": 20,
        "annualPricePesewas": 959040,
        "annualMonthlyEquivalentPesewas": 79920,
        "annualSavingPesewas": 239760,
        "currency": "GHS",
        "trialDays": 14,
        "transactionFeeBps": 50,
        "isPublic": true,
        "isDefaultForSignup": false,
        "highlighted": false,
        "badgeLabel": null,
        "bulletsHeading": "Everything in Growth, plus:",
        "marketingBullets": [
          "Unlimited products, staff and storage locations",
          "Stock transfers & stock conversion",
          "Accounting & bookkeeping",
          "Lowest transaction fee tier"
        ],
        "sortOrder": 30,
        "active": true,
        "updatedAt": "2026-11-27T09:00:00Z",
        "entitlements": [
          {"featureKey": "CUSTOM_DOMAIN", "enabled": true, "limitValue": null},
          {"featureKey": "THEME_ADVANCED", "enabled": true, "limitValue": null},
          {"featureKey": "MAX_PRODUCTS", "enabled": true, "limitValue": null},
          {"featureKey": "PRODUCT_FAMILIES", "enabled": true, "limitValue": null},
          {"featureKey": "WALK_IN_POS", "enabled": true, "limitValue": null},
          {"featureKey": "RETURNS_REFUNDS", "enabled": true, "limitValue": null},
          {"featureKey": "SHOP_WIDE_DISCOUNTS", "enabled": true, "limitValue": null},
          {"featureKey": "MAX_ACTIVE_COUPONS", "enabled": true, "limitValue": null},
          {"featureKey": "INVENTORY_MANAGEMENT", "enabled": true, "limitValue": null},
          {"featureKey": "STOCK_TRANSFERS", "enabled": true, "limitValue": null},
          {"featureKey": "STOCK_CONVERSION", "enabled": true, "limitValue": null},
          {"featureKey": "MAX_LOCATIONS", "enabled": true, "limitValue": null},
          {"featureKey": "SALES_REPORTS", "enabled": true, "limitValue": null},
          {"featureKey": "TAX_CONFIG", "enabled": true, "limitValue": null},
          {"featureKey": "ACCOUNTING", "enabled": true, "limitValue": null},
          {"featureKey": "MAX_STAFF", "enabled": true, "limitValue": null},
          {"featureKey": "LOGIN_OTP", "enabled": true, "limitValue": null}
        ],
        "version": 0,
        "updatedBy": null,
        "tenantCounts": {"TRIALING": 0, "ACTIVE": 3, "PAST_DUE": 0, "CANCELED": 0}
      }
    ],
    "features": [
      {"key": "CUSTOM_DOMAIN", "kind": "BOOLEAN", "label": "Custom domain", "group": "Storefront"},
      {
        "key": "THEME_ADVANCED",
        "kind": "BOOLEAN",
        "label": "Advanced theme customisation",
        "group": "Storefront"
      },
      {"key": "MAX_PRODUCTS", "kind": "LIMIT", "label": "Products", "group": "Catalog"},
      {
        "key": "PRODUCT_FAMILIES",
        "kind": "BOOLEAN",
        "label": "Product families and pack sizes",
        "group": "Catalog"
      },
      {"key": "WALK_IN_POS", "kind": "BOOLEAN", "label": "Walk-in point of sale", "group": "Orders"},
      {"key": "RETURNS_REFUNDS", "kind": "BOOLEAN", "label": "Returns and refunds", "group": "Orders"},
      {"key": "SHOP_WIDE_DISCOUNTS", "kind": "BOOLEAN", "label": "Shop-wide discounts", "group": "Marketing"},
      {"key": "MAX_ACTIVE_COUPONS", "kind": "LIMIT", "label": "Active coupons", "group": "Marketing"},
      {
        "key": "INVENTORY_MANAGEMENT",
        "kind": "BOOLEAN",
        "label": "Inventory adjustments and history",
        "group": "Inventory"
      },
      {
        "key": "STOCK_TRANSFERS",
        "kind": "BOOLEAN",
        "label": "Stock transfers between locations",
        "group": "Inventory"
      },
      {"key": "STOCK_CONVERSION", "kind": "BOOLEAN", "label": "Stock conversion", "group": "Inventory"},
      {"key": "MAX_LOCATIONS", "kind": "LIMIT", "label": "Storage locations", "group": "Inventory"},
      {"key": "SALES_REPORTS", "kind": "BOOLEAN", "label": "Sales reports", "group": "Finance and reports"},
      {"key": "TAX_CONFIG", "kind": "BOOLEAN", "label": "Tax configuration", "group": "Finance and reports"},
      {
        "key": "ACCOUNTING",
        "kind": "BOOLEAN",
        "label": "Accounting and bookkeeping",
        "group": "Finance and reports"
      },
      {
        "key": "MAX_STAFF",
        "kind": "LIMIT",
        "label": "Front-desk staff accounts",
        "group": "Team and security"
      },
      {"key": "LOGIN_OTP", "kind": "BOOLEAN", "label": "Login one-time codes", "group": "Team and security"}
    ]
  }
}
```

## 6. `POST /api/v1/platform/plans` and `PUT /api/v1/platform/plans/{id}`

**Create.** Body `PlanCreateRequest`: `code` plus every writable field of 3.1 (`name`, `monthlyPricePesewas`,
`annualDiscountPercent`, `trialDays`, `transactionFeeBps`, `isPublic`, `isDefaultForSignup`, `highlighted`,
`marketingBullets`, `sortOrder`, `active` required; `tagline`, `description`, `badgeLabel`, `bulletsHeading` optional,
absent = `null`). Answers `201` with the new plan (platform shape, `version` 0, entitlements all off / 0, counts 0).

<!-- example: plan-create-request valid PlanCreateRequest -->
```json
{
  "code": "ENTERPRISE",
  "name": "Enterprise",
  "tagline": null,
  "description": "Custom contracts for large brands.",
  "monthlyPricePesewas": 249900,
  "annualDiscountPercent": 25,
  "trialDays": 30,
  "transactionFeeBps": 40,
  "isPublic": false,
  "isDefaultForSignup": false,
  "highlighted": false,
  "badgeLabel": null,
  "bulletsHeading": null,
  "marketingBullets": [],
  "sortOrder": 40,
  "active": true
}
```

<!-- example: plan-created response PlanEnvelope 201 -->
```json
{
  "description": null,
  "data": {
    "id": 4,
    "code": "ENTERPRISE",
    "name": "Enterprise",
    "tagline": null,
    "description": "Custom contracts for large brands.",
    "monthlyPricePesewas": 249900,
    "annualDiscountPercent": 25,
    "annualPricePesewas": 2249100,
    "annualMonthlyEquivalentPesewas": 187425,
    "annualSavingPesewas": 749700,
    "currency": "GHS",
    "trialDays": 30,
    "transactionFeeBps": 40,
    "isPublic": false,
    "isDefaultForSignup": false,
    "highlighted": false,
    "badgeLabel": null,
    "bulletsHeading": null,
    "marketingBullets": [],
    "sortOrder": 40,
    "active": true,
    "updatedAt": "2026-11-27T10:15:00.25Z",
    "entitlements": [
      {"featureKey": "CUSTOM_DOMAIN", "enabled": false, "limitValue": null},
      {"featureKey": "THEME_ADVANCED", "enabled": false, "limitValue": null},
      {"featureKey": "MAX_PRODUCTS", "enabled": false, "limitValue": 0},
      {"featureKey": "PRODUCT_FAMILIES", "enabled": false, "limitValue": null},
      {"featureKey": "WALK_IN_POS", "enabled": false, "limitValue": null},
      {"featureKey": "RETURNS_REFUNDS", "enabled": false, "limitValue": null},
      {"featureKey": "SHOP_WIDE_DISCOUNTS", "enabled": false, "limitValue": null},
      {"featureKey": "MAX_ACTIVE_COUPONS", "enabled": false, "limitValue": 0},
      {"featureKey": "INVENTORY_MANAGEMENT", "enabled": false, "limitValue": null},
      {"featureKey": "STOCK_TRANSFERS", "enabled": false, "limitValue": null},
      {"featureKey": "STOCK_CONVERSION", "enabled": false, "limitValue": null},
      {"featureKey": "MAX_LOCATIONS", "enabled": false, "limitValue": 0},
      {"featureKey": "SALES_REPORTS", "enabled": false, "limitValue": null},
      {"featureKey": "TAX_CONFIG", "enabled": false, "limitValue": null},
      {"featureKey": "ACCOUNTING", "enabled": false, "limitValue": null},
      {"featureKey": "MAX_STAFF", "enabled": false, "limitValue": 0},
      {"featureKey": "LOGIN_OTP", "enabled": false, "limitValue": null}
    ],
    "version": 0,
    "updatedBy": "root@platform.example",
    "tenantCounts": {"TRIALING": 0, "ACTIVE": 0, "PAST_DUE": 0, "CANCELED": 0}
  }
}
```

**Update.** Body `PlanUpdateRequest`: `version` (the version the client read) plus the same writable fields; it is a
**full replace** (an absent optional text is stored as `null`, send the current value of fields you do not change).
`code` is not part of the body; a `code` that differs from the stored one is `400` (`code`: `code cannot be changed`),
an equal one is ignored. Answers `200` with the saved plan (new `version`). A body equal to the stored values writes
nothing: no version bump, no audit row, `200` with the unchanged plan. Unknown `{id}`: `404 PLAN_NOT_FOUND`. Stale
`version`: `409 PLAN_VERSION_CONFLICT` (9).

<!-- example: plan-update-request valid PlanUpdateRequest -->
```json
{
  "version": 3,
  "name": "Growth",
  "tagline": "FOR ESTABLISHED SELLERS",
  "description": "Full brand ownership and tools to maximize conversions.",
  "monthlyPricePesewas": 44900,
  "annualDiscountPercent": 15,
  "trialDays": 14,
  "transactionFeeBps": 100,
  "isPublic": true,
  "isDefaultForSignup": false,
  "highlighted": true,
  "badgeLabel": "Most popular",
  "bulletsHeading": "Everything in Starter, plus:",
  "marketingBullets": [
    "Custom domain connection (yourbrand.com)",
    "Up to 1,000 products & inventory management",
    "Full storefront customisation (theme & layout editor)",
    "Shop-wide discounts, returns & refunds",
    "Tax configuration & sales reports",
    "Walk-in point of sale",
    "Lower platform transaction fees"
  ],
  "sortOrder": 20,
  "active": true
}
```

<!-- example: plan-updated response PlanEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "id": 2,
    "code": "GROWTH",
    "name": "Growth",
    "tagline": "FOR ESTABLISHED SELLERS",
    "description": "Full brand ownership and tools to maximize conversions.",
    "monthlyPricePesewas": 44900,
    "annualDiscountPercent": 15,
    "annualPricePesewas": 457980,
    "annualMonthlyEquivalentPesewas": 38165,
    "annualSavingPesewas": 80820,
    "currency": "GHS",
    "trialDays": 14,
    "transactionFeeBps": 100,
    "isPublic": true,
    "isDefaultForSignup": false,
    "highlighted": true,
    "badgeLabel": "Most popular",
    "bulletsHeading": "Everything in Starter, plus:",
    "marketingBullets": [
      "Custom domain connection (yourbrand.com)",
      "Up to 1,000 products & inventory management",
      "Full storefront customisation (theme & layout editor)",
      "Shop-wide discounts, returns & refunds",
      "Tax configuration & sales reports",
      "Walk-in point of sale",
      "Lower platform transaction fees"
    ],
    "sortOrder": 20,
    "active": true,
    "updatedAt": "2026-11-27T11:00:00Z",
    "entitlements": [
      {"featureKey": "CUSTOM_DOMAIN", "enabled": true, "limitValue": null},
      {"featureKey": "THEME_ADVANCED", "enabled": true, "limitValue": null},
      {"featureKey": "MAX_PRODUCTS", "enabled": true, "limitValue": 1000},
      {"featureKey": "PRODUCT_FAMILIES", "enabled": true, "limitValue": null},
      {"featureKey": "WALK_IN_POS", "enabled": true, "limitValue": null},
      {"featureKey": "RETURNS_REFUNDS", "enabled": true, "limitValue": null},
      {"featureKey": "SHOP_WIDE_DISCOUNTS", "enabled": true, "limitValue": null},
      {"featureKey": "MAX_ACTIVE_COUPONS", "enabled": true, "limitValue": null},
      {"featureKey": "INVENTORY_MANAGEMENT", "enabled": true, "limitValue": null},
      {"featureKey": "STOCK_TRANSFERS", "enabled": false, "limitValue": null},
      {"featureKey": "STOCK_CONVERSION", "enabled": false, "limitValue": null},
      {"featureKey": "MAX_LOCATIONS", "enabled": true, "limitValue": 1},
      {"featureKey": "SALES_REPORTS", "enabled": true, "limitValue": null},
      {"featureKey": "TAX_CONFIG", "enabled": true, "limitValue": null},
      {"featureKey": "ACCOUNTING", "enabled": false, "limitValue": null},
      {"featureKey": "MAX_STAFF", "enabled": true, "limitValue": 3},
      {"featureKey": "LOGIN_OTP", "enabled": true, "limitValue": null}
    ],
    "version": 4,
    "updatedBy": "root@platform.example",
    "tenantCounts": {"TRIALING": 2, "ACTIVE": 10, "PAST_DUE": 0, "CANCELED": 0}
  }
}
```

Errors of both (and of the matrix):

| HTTP | When | Body |
|---|---|---|
| 400 | a field missing, of the wrong type, out of range (3.1), too long, control characters, more than 12 bullets, a blank bullet | `Validation failed` + field map |
| 400 | a plan rule of section 7 | `Validation failed` + field map naming the field |
| 400 | body not JSON / unreadable | `Request body is missing or malformed.` |
| 401 / 403 | not signed in as a platform admin / not SUPER_ADMIN | section 4 |
| 404 | `{id}` (or a matrix `id`) unknown | `{"description": "Plan not found", "data": {"code": "PLAN_NOT_FOUND"}}` |
| 409 | create with a `code` that exists (case-sensitive; codes are upper case) | `{"description": "A plan with this code already exists", "data": {"code": "PLAN_CODE_TAKEN"}}` |
| 409 | stale `version` | `PLAN_VERSION_CONFLICT`, section 9 |

<!-- example: plan-validation-error response ValidationErrorEnvelope 400 -->
```json
{ "description": "Validation failed", "data": { "annualDiscountPercent": "must be less than or equal to 100" } }
```

<!-- example: plan-rule-error response ValidationErrorEnvelope 400 -->
```json
{ "description": "Validation failed", "data": { "active": "the default sign-up plan cannot be deactivated" } }
```

<!-- example: plan-code-taken response CodedErrorEnvelope 409 -->
```json
{ "description": "A plan with this code already exists", "data": { "code": "PLAN_CODE_TAKEN" } }
```

<!-- example: plan-not-found response CodedErrorEnvelope 404 -->
```json
{ "description": "Plan not found", "data": { "code": "PLAN_NOT_FOUND" } }
```

Rejected request examples (each fails the schema and the server answers `400`):

<!-- example: plan-create-bad-code invalid PlanCreateRequest 400 -->
```json
{ "code": "growth", "name": "Growth", "monthlyPricePesewas": 39900, "annualDiscountPercent": 20, "trialDays": 14, "transactionFeeBps": 100, "isPublic": true, "isDefaultForSignup": false, "highlighted": false, "marketingBullets": [], "sortOrder": 20, "active": true }
```

<!-- example: plan-create-discount-over-100 invalid PlanCreateRequest 400 -->
```json
{ "code": "BIG_SALE", "name": "Big sale", "monthlyPricePesewas": 39900, "annualDiscountPercent": 101, "trialDays": 14, "transactionFeeBps": 100, "isPublic": true, "isDefaultForSignup": false, "highlighted": false, "marketingBullets": [], "sortOrder": 20, "active": true }
```

<!-- example: plan-create-fractional-price invalid PlanCreateRequest 400 -->
```json
{ "code": "DECIMAL", "name": "Decimal", "monthlyPricePesewas": 149.5, "annualDiscountPercent": 20, "trialDays": 14, "transactionFeeBps": 100, "isPublic": true, "isDefaultForSignup": false, "highlighted": false, "marketingBullets": [], "sortOrder": 20, "active": true }
```

<!-- example: plan-update-trial-too-long invalid PlanUpdateRequest 400 -->
```json
{ "version": 3, "name": "Growth", "monthlyPricePesewas": 39900, "annualDiscountPercent": 20, "trialDays": 366, "transactionFeeBps": 100, "isPublic": true, "isDefaultForSignup": false, "highlighted": true, "marketingBullets": [], "sortOrder": 20, "active": true }
```

<!-- example: plan-update-fee-too-high invalid PlanUpdateRequest 400 -->
```json
{ "version": 3, "name": "Growth", "monthlyPricePesewas": 39900, "annualDiscountPercent": 20, "trialDays": 14, "transactionFeeBps": 10001, "isPublic": true, "isDefaultForSignup": false, "highlighted": true, "marketingBullets": [], "sortOrder": 20, "active": true }
```

<!-- example: plan-update-missing-version invalid PlanUpdateRequest 400 -->
```json
{ "name": "Growth", "monthlyPricePesewas": 39900, "annualDiscountPercent": 20, "trialDays": 14, "transactionFeeBps": 100, "isPublic": true, "isDefaultForSignup": false, "highlighted": true, "marketingBullets": [], "sortOrder": 20, "active": true }
```

## 7. Plan rules (checked on create, update and on the final state of a matrix write)

Violations are `400 Validation failed` with the field named; nothing is written.

| Rule | Field in the 400 |
|---|---|
| Exactly one plan is `isDefaultForSignup`. Setting it `true` on a plan **moves** the flag: the previous default is cleared in the same transaction (its `version` goes up and it gets its own audit row). Setting it `false` on the current default without another plan taking it is refused | `isDefaultForSignup`: `another plan must become the default first` |
| The default sign-up plan must be `active` | `active`: `the default sign-up plan cannot be deactivated` (or, when setting the flag on an inactive plan, `isDefaultForSignup`: `an inactive plan cannot be the default`) |
| At least one plan must stay both `isPublic` and `active` | `isPublic` or `active`: `at least one public active plan must remain` |
| In one matrix request, at most one item may set `isDefaultForSignup: true` | `plans[i].plan.isDefaultForSignup`: `only one plan can be the default` |

The default plan does not have to be public (an unlisted "launch" plan is allowed). Deactivating a plan or making it
non-public removes it from the public list at once; tenants on it keep working with its entitlements, and the platform
console still lists it.

## 8. `PUT /api/v1/platform/plans/matrix`

Updates several plans in **one transaction, all-or-nothing**. Body `PlanMatrixRequest`:

- `plans`: 1..50 items, each `{ "id", "version", "entitlements"?, "plan"? }` with at least one of `entitlements` / `plan`;
  the same `id` twice is `400`.
- `entitlements`: the entries to change (others keep their stored value). BOOLEAN: `{ "featureKey", "enabled" }`;
  LIMIT: `{ "featureKey", "limitValue" }` (`null` = unlimited). Rules in 3.3.
- `plan`: optional full replacement of the plan's writable fields (same body as `PUT /{id}` without `version`).

Every item's `version` is checked first. If ANY item is stale, nothing is written and the answer is
`409 PLAN_VERSION_CONFLICT` listing **every** stale item (section 9). Then all validation and the rules of section 7 are
checked on the final state; any failure is `400` and nothing is written. Unknown `id`: `404 PLAN_NOT_FOUND`, nothing
written. An item whose values equal the stored ones is version-checked but writes nothing (no bump, no audit row).

On success: `200` with the same body as `GET /platform/plans` (all plans, new versions, catalog), so the client replaces
its whole state. Each changed plan gets one audit row (`PLAN_MATRIX_UPDATE`). Entitlement changes are effective for every
tenant on the plan from the next request after the commit (cache evicted after commit, PLAN-02 AC1).

Example: move `WALK_IN_POS` out of Growth (Scale already has it), give Starter 100 products and a 30-day trial:

<!-- example: matrix-request valid PlanMatrixRequest -->
```json
{
  "plans": [
    {"id": 2, "version": 4, "entitlements": [{"featureKey": "WALK_IN_POS", "enabled": false}]},
    {
      "id": 1,
      "version": 7,
      "entitlements": [{"featureKey": "MAX_PRODUCTS", "limitValue": 100}],
      "plan": {
        "name": "Starter",
        "tagline": "FOR YOUR FIRST SALES",
        "description": "Everything you need to launch your first online store.",
        "monthlyPricePesewas": 14900,
        "annualDiscountPercent": 20,
        "trialDays": 30,
        "transactionFeeBps": 150,
        "isPublic": true,
        "isDefaultForSignup": true,
        "highlighted": false,
        "badgeLabel": null,
        "bulletsHeading": "Your launch essentials",
        "marketingBullets": [
          "Free platform subdomain (yourstore.shopfi.store)",
          "Up to 50 products",
          "Integrated online checkout & payment processing",
          "Up to 3 active coupon codes",
          "Standard storefront templates"
        ],
        "sortOrder": 10,
        "active": true
      }
    }
  ]
}
```

<!-- example: matrix-response response PlanCatalogEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "plans": [
      {
        "id": 1,
        "code": "STARTER",
        "name": "Starter",
        "tagline": "FOR YOUR FIRST SALES",
        "description": "Everything you need to launch your first online store.",
        "monthlyPricePesewas": 14900,
        "annualDiscountPercent": 20,
        "annualPricePesewas": 143040,
        "annualMonthlyEquivalentPesewas": 11920,
        "annualSavingPesewas": 35760,
        "currency": "GHS",
        "trialDays": 30,
        "transactionFeeBps": 150,
        "isPublic": true,
        "isDefaultForSignup": true,
        "highlighted": false,
        "badgeLabel": null,
        "bulletsHeading": "Your launch essentials",
        "marketingBullets": [
          "Free platform subdomain (yourstore.shopfi.store)",
          "Up to 50 products",
          "Integrated online checkout & payment processing",
          "Up to 3 active coupon codes",
          "Standard storefront templates"
        ],
        "sortOrder": 10,
        "active": true,
        "updatedAt": "2026-11-27T12:00:00.5Z",
        "entitlements": [
          {"featureKey": "CUSTOM_DOMAIN", "enabled": false, "limitValue": null},
          {"featureKey": "THEME_ADVANCED", "enabled": false, "limitValue": null},
          {"featureKey": "MAX_PRODUCTS", "enabled": true, "limitValue": 100},
          {"featureKey": "PRODUCT_FAMILIES", "enabled": false, "limitValue": null},
          {"featureKey": "WALK_IN_POS", "enabled": false, "limitValue": null},
          {"featureKey": "RETURNS_REFUNDS", "enabled": false, "limitValue": null},
          {"featureKey": "SHOP_WIDE_DISCOUNTS", "enabled": false, "limitValue": null},
          {"featureKey": "MAX_ACTIVE_COUPONS", "enabled": true, "limitValue": 3},
          {"featureKey": "INVENTORY_MANAGEMENT", "enabled": false, "limitValue": null},
          {"featureKey": "STOCK_TRANSFERS", "enabled": false, "limitValue": null},
          {"featureKey": "STOCK_CONVERSION", "enabled": false, "limitValue": null},
          {"featureKey": "MAX_LOCATIONS", "enabled": true, "limitValue": 1},
          {"featureKey": "SALES_REPORTS", "enabled": false, "limitValue": null},
          {"featureKey": "TAX_CONFIG", "enabled": false, "limitValue": null},
          {"featureKey": "ACCOUNTING", "enabled": false, "limitValue": null},
          {"featureKey": "MAX_STAFF", "enabled": false, "limitValue": 0},
          {"featureKey": "LOGIN_OTP", "enabled": false, "limitValue": null}
        ],
        "version": 8,
        "updatedBy": "root@platform.example",
        "tenantCounts": {"TRIALING": 9, "ACTIVE": 0, "PAST_DUE": 0, "CANCELED": 1}
      },
      {
        "id": 2,
        "code": "GROWTH",
        "name": "Growth",
        "tagline": "FOR ESTABLISHED SELLERS",
        "description": "Full brand ownership and tools to maximize conversions.",
        "monthlyPricePesewas": 44900,
        "annualDiscountPercent": 15,
        "annualPricePesewas": 457980,
        "annualMonthlyEquivalentPesewas": 38165,
        "annualSavingPesewas": 80820,
        "currency": "GHS",
        "trialDays": 14,
        "transactionFeeBps": 100,
        "isPublic": true,
        "isDefaultForSignup": false,
        "highlighted": true,
        "badgeLabel": "Most popular",
        "bulletsHeading": "Everything in Starter, plus:",
        "marketingBullets": [
          "Custom domain connection (yourbrand.com)",
          "Up to 1,000 products & inventory management",
          "Full storefront customisation (theme & layout editor)",
          "Shop-wide discounts, returns & refunds",
          "Tax configuration & sales reports",
          "Walk-in point of sale",
          "Lower platform transaction fees"
        ],
        "sortOrder": 20,
        "active": true,
        "updatedAt": "2026-11-27T12:00:00.5Z",
        "entitlements": [
          {"featureKey": "CUSTOM_DOMAIN", "enabled": true, "limitValue": null},
          {"featureKey": "THEME_ADVANCED", "enabled": true, "limitValue": null},
          {"featureKey": "MAX_PRODUCTS", "enabled": true, "limitValue": 1000},
          {"featureKey": "PRODUCT_FAMILIES", "enabled": true, "limitValue": null},
          {"featureKey": "WALK_IN_POS", "enabled": false, "limitValue": null},
          {"featureKey": "RETURNS_REFUNDS", "enabled": true, "limitValue": null},
          {"featureKey": "SHOP_WIDE_DISCOUNTS", "enabled": true, "limitValue": null},
          {"featureKey": "MAX_ACTIVE_COUPONS", "enabled": true, "limitValue": null},
          {"featureKey": "INVENTORY_MANAGEMENT", "enabled": true, "limitValue": null},
          {"featureKey": "STOCK_TRANSFERS", "enabled": false, "limitValue": null},
          {"featureKey": "STOCK_CONVERSION", "enabled": false, "limitValue": null},
          {"featureKey": "MAX_LOCATIONS", "enabled": true, "limitValue": 1},
          {"featureKey": "SALES_REPORTS", "enabled": true, "limitValue": null},
          {"featureKey": "TAX_CONFIG", "enabled": true, "limitValue": null},
          {"featureKey": "ACCOUNTING", "enabled": false, "limitValue": null},
          {"featureKey": "MAX_STAFF", "enabled": true, "limitValue": 3},
          {"featureKey": "LOGIN_OTP", "enabled": true, "limitValue": null}
        ],
        "version": 5,
        "updatedBy": "root@platform.example",
        "tenantCounts": {"TRIALING": 2, "ACTIVE": 10, "PAST_DUE": 0, "CANCELED": 0}
      },
      {
        "id": 3,
        "code": "SCALE",
        "name": "Scale",
        "tagline": "FOR HIGH-VOLUME BRANDS",
        "description": "Advanced operations, team access, and lowest fees.",
        "monthlyPricePesewas": 99900,
        "annualDiscountPercent": 20,
        "annualPricePesewas": 959040,
        "annualMonthlyEquivalentPesewas": 79920,
        "annualSavingPesewas": 239760,
        "currency": "GHS",
        "trialDays": 14,
        "transactionFeeBps": 50,
        "isPublic": true,
        "isDefaultForSignup": false,
        "highlighted": false,
        "badgeLabel": null,
        "bulletsHeading": "Everything in Growth, plus:",
        "marketingBullets": [
          "Unlimited products, staff and storage locations",
          "Stock transfers & stock conversion",
          "Accounting & bookkeeping",
          "Lowest transaction fee tier"
        ],
        "sortOrder": 30,
        "active": true,
        "updatedAt": "2026-11-27T09:00:00Z",
        "entitlements": [
          {"featureKey": "CUSTOM_DOMAIN", "enabled": true, "limitValue": null},
          {"featureKey": "THEME_ADVANCED", "enabled": true, "limitValue": null},
          {"featureKey": "MAX_PRODUCTS", "enabled": true, "limitValue": null},
          {"featureKey": "PRODUCT_FAMILIES", "enabled": true, "limitValue": null},
          {"featureKey": "WALK_IN_POS", "enabled": true, "limitValue": null},
          {"featureKey": "RETURNS_REFUNDS", "enabled": true, "limitValue": null},
          {"featureKey": "SHOP_WIDE_DISCOUNTS", "enabled": true, "limitValue": null},
          {"featureKey": "MAX_ACTIVE_COUPONS", "enabled": true, "limitValue": null},
          {"featureKey": "INVENTORY_MANAGEMENT", "enabled": true, "limitValue": null},
          {"featureKey": "STOCK_TRANSFERS", "enabled": true, "limitValue": null},
          {"featureKey": "STOCK_CONVERSION", "enabled": true, "limitValue": null},
          {"featureKey": "MAX_LOCATIONS", "enabled": true, "limitValue": null},
          {"featureKey": "SALES_REPORTS", "enabled": true, "limitValue": null},
          {"featureKey": "TAX_CONFIG", "enabled": true, "limitValue": null},
          {"featureKey": "ACCOUNTING", "enabled": true, "limitValue": null},
          {"featureKey": "MAX_STAFF", "enabled": true, "limitValue": null},
          {"featureKey": "LOGIN_OTP", "enabled": true, "limitValue": null}
        ],
        "version": 0,
        "updatedBy": null,
        "tenantCounts": {"TRIALING": 0, "ACTIVE": 3, "PAST_DUE": 0, "CANCELED": 0}
      }
    ],
    "features": [
      {"key": "CUSTOM_DOMAIN", "kind": "BOOLEAN", "label": "Custom domain", "group": "Storefront"},
      {
        "key": "THEME_ADVANCED",
        "kind": "BOOLEAN",
        "label": "Advanced theme customisation",
        "group": "Storefront"
      },
      {"key": "MAX_PRODUCTS", "kind": "LIMIT", "label": "Products", "group": "Catalog"},
      {
        "key": "PRODUCT_FAMILIES",
        "kind": "BOOLEAN",
        "label": "Product families and pack sizes",
        "group": "Catalog"
      },
      {"key": "WALK_IN_POS", "kind": "BOOLEAN", "label": "Walk-in point of sale", "group": "Orders"},
      {"key": "RETURNS_REFUNDS", "kind": "BOOLEAN", "label": "Returns and refunds", "group": "Orders"},
      {"key": "SHOP_WIDE_DISCOUNTS", "kind": "BOOLEAN", "label": "Shop-wide discounts", "group": "Marketing"},
      {"key": "MAX_ACTIVE_COUPONS", "kind": "LIMIT", "label": "Active coupons", "group": "Marketing"},
      {
        "key": "INVENTORY_MANAGEMENT",
        "kind": "BOOLEAN",
        "label": "Inventory adjustments and history",
        "group": "Inventory"
      },
      {
        "key": "STOCK_TRANSFERS",
        "kind": "BOOLEAN",
        "label": "Stock transfers between locations",
        "group": "Inventory"
      },
      {"key": "STOCK_CONVERSION", "kind": "BOOLEAN", "label": "Stock conversion", "group": "Inventory"},
      {"key": "MAX_LOCATIONS", "kind": "LIMIT", "label": "Storage locations", "group": "Inventory"},
      {"key": "SALES_REPORTS", "kind": "BOOLEAN", "label": "Sales reports", "group": "Finance and reports"},
      {"key": "TAX_CONFIG", "kind": "BOOLEAN", "label": "Tax configuration", "group": "Finance and reports"},
      {
        "key": "ACCOUNTING",
        "kind": "BOOLEAN",
        "label": "Accounting and bookkeeping",
        "group": "Finance and reports"
      },
      {
        "key": "MAX_STAFF",
        "kind": "LIMIT",
        "label": "Front-desk staff accounts",
        "group": "Team and security"
      },
      {"key": "LOGIN_OTP", "kind": "BOOLEAN", "label": "Login one-time codes", "group": "Team and security"}
    ]
  }
}
```

Errors specific to the matrix (plus the table of section 6):

<!-- example: matrix-wrong-kind-error response ValidationErrorEnvelope 400 -->
```json
{ "description": "Validation failed", "data": { "plans[0].entitlements[0].limitValue": "must be null for a BOOLEAN feature" } }
```

<!-- example: matrix-unknown-feature-error response ValidationErrorEnvelope 400 -->
```json
{ "description": "Validation failed", "data": { "plans[0].entitlements[0].featureKey": "unknown feature" } }
```

<!-- example: matrix-negative-limit invalid PlanMatrixRequest 400 -->
```json
{ "plans": [ { "id": 1, "version": 7, "entitlements": [ { "featureKey": "MAX_PRODUCTS", "limitValue": -1 } ] } ] }
```

<!-- example: matrix-item-without-change invalid PlanMatrixRequest 400 -->
```json
{ "plans": [ { "id": 1, "version": 7 } ] }
```

<!-- example: matrix-empty invalid PlanMatrixRequest 400 -->
```json
{ "plans": [] }
```

## 9. Optimistic locking: `409 PLAN_VERSION_CONFLICT`

Every plan write carries the `version` the client read. If the stored version differs, nothing is written and the
answer is:

<!-- example: matrix-conflict response PlanVersionConflictEnvelope 409 -->
```json
{
  "description": "Another admin changed these plans. Reload them and apply your changes again.",
  "data": {
    "code": "PLAN_VERSION_CONFLICT",
    "conflicts": [ { "planId": 2, "expectedVersion": 4, "currentVersion": 5 } ]
  }
}
```

`conflicts` lists every stale plan of the request (one entry for `PUT /{id}`), `expectedVersion` = what the client sent,
`currentVersion` = what is stored. Two writers from the same version: exactly one wins, the other gets this 409. The
server never retries or merges. This is a dedicated code: the generic JPA optimistic-lock handler (`503 TRY_AGAIN`,
errors.md) must not answer plan writes. The console keeps the user's edits, shows which plans changed, and offers
"reload latest" or "review" (PLAN-04 AC5); it never re-sends automatically with the new version.

Note that moving `isDefaultForSignup` (section 7) bumps the version of the plan that loses the flag, so a client holding
that plan gets a 409 on its next write of it.

## 10. `GET /api/v1/platform/plans/audit`

Query: `page` (0-based, default 0, `0..1000000`), `size` (default 20, capped at 100; the response reports the effective
size), `planId` (optional, only rows of that plan), `tenantSlug` (optional, only rows of that tenant). Newest first
(`id` descending); bad parameters are `400` like the tenant list. Every create, update, matrix change and tenant plan
change writes one row per affected plan or tenant, in the same transaction as the change.

Row keys (exactly these): `id`, `occurredAt` (timestamp), `actorEmail` (platform admin; SUPER_ADMIN data, do not log or
cache in shared storage), `action` (`PLAN_CREATE`, `PLAN_UPDATE`, `PLAN_MATRIX_UPDATE`, `TENANT_PLAN_UPDATE`), `planId` /
`planCode` (the plan; for `TENANT_PLAN_UPDATE` the tenant's plan after the change), `tenantSlug` (only for
`TENANT_PLAN_UPDATE`, else `null`), `changes` (only changed fields: `{ "field", "before", "after" }`). Field names:

- plan fields by their JSON name (`monthlyPricePesewas`, `marketingBullets`, ...); `before` / `after` are the JSON
  values (`null` on create for `before`);
- entitlements as `entitlements.<KEY>`: BOOLEAN `before` / `after` are booleans, LIMIT ones the limit (`null` =
  unlimited);
- tenant changes as `planCode`, `trialEndsAt`, `subscriptionStatus`.

Values are stored data: render them as text only. Derived fields are never audited (they follow from price and percent).
Tenant plan changes ALSO appear in `GET /api/v1/platform/tenants/{slug}/audit` (platform-tenants.md).

<!-- example: plan-audit-page response PlanAuditPageEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "items": [
      {
        "id": 103,
        "occurredAt": "2026-11-27T13:05:00.000001Z",
        "actorEmail": "root@platform.example",
        "action": "TENANT_PLAN_UPDATE",
        "planId": 3,
        "planCode": "SCALE",
        "tenantSlug": "acme",
        "changes": [ { "field": "planCode", "before": "GROWTH", "after": "SCALE" } ]
      },
      {
        "id": 102,
        "occurredAt": "2026-11-27T12:00:00.5Z",
        "actorEmail": "root@platform.example",
        "action": "PLAN_MATRIX_UPDATE",
        "planId": 1,
        "planCode": "STARTER",
        "tenantSlug": null,
        "changes": [
          { "field": "trialDays", "before": 14, "after": 30 },
          { "field": "entitlements.MAX_PRODUCTS", "before": 50, "after": 100 }
        ]
      },
      {
        "id": 101,
        "occurredAt": "2026-11-27T12:00:00.5Z",
        "actorEmail": "root@platform.example",
        "action": "PLAN_MATRIX_UPDATE",
        "planId": 2,
        "planCode": "GROWTH",
        "tenantSlug": null,
        "changes": [ { "field": "entitlements.WALK_IN_POS", "before": true, "after": false } ]
      },
      {
        "id": 100,
        "occurredAt": "2026-11-27T11:00:00Z",
        "actorEmail": "root@platform.example",
        "action": "PLAN_UPDATE",
        "planId": 2,
        "planCode": "GROWTH",
        "tenantSlug": null,
        "changes": [
          { "field": "monthlyPricePesewas", "before": 39900, "after": 44900 },
          { "field": "annualDiscountPercent", "before": 20, "after": 15 }
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

## 11. `PUT /api/v1/platform/tenants/{slug}/plan`

Sets one tenant's plan, trial end and/or subscription status (the manual bridge until PLAN-06 billing). `{slug}` follows
platform-tenants.md (unknown or malformed: `404 {"description": "Tenant not found", "data": null}`). It counts against the
per-admin action throttle of platform-tenants.md (`429`). Body `TenantPlanUpdateRequest`, at least one of:

| Field | Rules |
|---|---|
| `planId` | an existing plan id (else `400`, `planId`: `unknown plan`). An inactive plan is refused with `400 PLAN_INACTIVE` unless `allowInactive: true` is also sent. Changing the plan keeps `subscriptionStatus` and `trialEndsAt` |
| `trialEndsAt` | timestamp, **extend only**: strictly later than the current `trialEndsAt` (or than now, when it is `null` or past) and at most 365 days after now. Allowed only while the tenant's status is `TRIALING` or `CANCELED`; it sets `subscriptionStatus` to `TRIALING` (a canceled or expired trial is revived). Not combinable with `subscriptionStatus` in one request |
| `subscriptionStatus` | `ACTIVE` or `CANCELED` only (`TRIALING` comes from sign-up or `trialEndsAt`, `PAST_DUE` from PLAN-06); anything else `400`. `trialEndsAt` is kept as history |
| `allowInactive` | boolean, default `false`; only meaningful with `planId` |

Answers `200` with the tenant summary (platform-tenants.md shape with the plan fields). A request that changes nothing
answers `200` and writes nothing. A change writes one `TENANT_PLAN_UPDATE` row (section 10) and one row in the tenant
audit log (`action` `CHANGE_PLAN`, `phase` `OUTCOME`, `previousStatus` = `newStatus` = the tenant's registry status), and
evicts the tenant's cached plan/status (PLAN-02 AC1): the change is effective on the tenant's next request.

<!-- example: tenant-plan-change-request valid TenantPlanUpdateRequest -->
```json
{ "planId": 3 }
```

<!-- example: tenant-plan-inactive-request valid TenantPlanUpdateRequest -->
```json
{ "planId": 4, "allowInactive": true }
```

<!-- example: tenant-trial-extend-request valid TenantPlanUpdateRequest -->
```json
{ "trialEndsAt": "2026-12-31T23:59:59Z" }
```

<!-- example: tenant-status-request valid TenantPlanUpdateRequest -->
```json
{ "subscriptionStatus": "ACTIVE" }
```

<!-- example: tenant-plan-response response TenantSummaryEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "id": 12,
    "slug": "acme",
    "displayName": "Acme Perfumes",
    "status": "ACTIVE",
    "plan": "SCALE",
    "planName": "Scale",
    "subscriptionStatus": "TRIALING",
    "trialEndsAt": "2026-12-11T09:30:00Z",
    "ownerEmail": "owner@acme.example",
    "customDomain": null,
    "customDomainStatus": null,
    "createdAt": "2026-11-01T09:30:00.123456",
    "updatedAt": "2026-11-27T13:05:00.000001"
  }
}
```

<!-- example: tenant-plan-inactive-error response CodedErrorEnvelope 400 -->
```json
{ "description": "The plan is inactive; send allowInactive to assign it anyway", "data": { "code": "PLAN_INACTIVE" } }
```

<!-- example: tenant-trial-error response ValidationErrorEnvelope 400 -->
```json
{ "description": "Validation failed", "data": { "trialEndsAt": "must be later than the current trial end and at most 365 days ahead" } }
```

<!-- example: tenant-plan-empty invalid TenantPlanUpdateRequest 400 -->
```json
{}
```

<!-- example: tenant-status-trialing invalid TenantPlanUpdateRequest 400 -->
```json
{ "subscriptionStatus": "TRIALING" }
```

## 12. `GET /api/v1/platform/onboarding/plans` (public)

The pricing page (PLAN-05, platform host, server-side fetch) and the signup wizard read this. It lives under
`/platform/onboarding/*` because tenant-less public endpoints must (a bare `/api/v1/plans` would hit tenant resolution
on the platform host). `permitAll` (a new explicit `GET` matcher next to `/onboarding/status`), no cookie, no tenant,
onboarding CORS zone (onboarding.md section 11: GET allowed, credentials not allowed).

`data.plans`: plans that are `isPublic` **and** `active`, in `sortOrder` then `id`, in the public shape (no `version`,
`updatedBy`, `tenantCounts`), with entitlements. `data.features`: the catalog. Zero public plans is a valid `200` with an
empty list ("Plans coming soon", PLAN-05 AC7). Response header `Cache-Control: public, max-age=60`; a change made in the
console may take up to 60 s to show here. No per-IP limiter (the body is identical for everyone and cacheable).

The default sign-up plan is the one with `isDefaultForSignup: true`; if it is not public it is absent from this list and
the wizard simply hides the "you start on ..." line (PLAN-05 AC8). The trial line uses `trialDays` (hidden when 0). The
"Annual · Save X%" toggle label is computed by the client from the `annualDiscountPercent` values of the shown plans
(same for all: "Save X%"; different: "Save up to X%" with the highest; all 0: no "Save"), a display rule, not money maths.

<!-- example: public-plans response PublicPlansEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "plans": [
      {
        "id": 1,
        "code": "STARTER",
        "name": "Starter",
        "tagline": "FOR YOUR FIRST SALES",
        "description": "Everything you need to launch your first online store.",
        "monthlyPricePesewas": 14900,
        "annualDiscountPercent": 20,
        "annualPricePesewas": 143040,
        "annualMonthlyEquivalentPesewas": 11920,
        "annualSavingPesewas": 35760,
        "currency": "GHS",
        "trialDays": 30,
        "transactionFeeBps": 150,
        "isPublic": true,
        "isDefaultForSignup": true,
        "highlighted": false,
        "badgeLabel": null,
        "bulletsHeading": "Your launch essentials",
        "marketingBullets": [
          "Free platform subdomain (yourstore.shopfi.store)",
          "Up to 50 products",
          "Integrated online checkout & payment processing",
          "Up to 3 active coupon codes",
          "Standard storefront templates"
        ],
        "sortOrder": 10,
        "active": true,
        "updatedAt": "2026-11-27T12:00:00.5Z",
        "entitlements": [
          {"featureKey": "CUSTOM_DOMAIN", "enabled": false, "limitValue": null},
          {"featureKey": "THEME_ADVANCED", "enabled": false, "limitValue": null},
          {"featureKey": "MAX_PRODUCTS", "enabled": true, "limitValue": 100},
          {"featureKey": "PRODUCT_FAMILIES", "enabled": false, "limitValue": null},
          {"featureKey": "WALK_IN_POS", "enabled": false, "limitValue": null},
          {"featureKey": "RETURNS_REFUNDS", "enabled": false, "limitValue": null},
          {"featureKey": "SHOP_WIDE_DISCOUNTS", "enabled": false, "limitValue": null},
          {"featureKey": "MAX_ACTIVE_COUPONS", "enabled": true, "limitValue": 3},
          {"featureKey": "INVENTORY_MANAGEMENT", "enabled": false, "limitValue": null},
          {"featureKey": "STOCK_TRANSFERS", "enabled": false, "limitValue": null},
          {"featureKey": "STOCK_CONVERSION", "enabled": false, "limitValue": null},
          {"featureKey": "MAX_LOCATIONS", "enabled": true, "limitValue": 1},
          {"featureKey": "SALES_REPORTS", "enabled": false, "limitValue": null},
          {"featureKey": "TAX_CONFIG", "enabled": false, "limitValue": null},
          {"featureKey": "ACCOUNTING", "enabled": false, "limitValue": null},
          {"featureKey": "MAX_STAFF", "enabled": false, "limitValue": 0},
          {"featureKey": "LOGIN_OTP", "enabled": false, "limitValue": null}
        ]
      },
      {
        "id": 2,
        "code": "GROWTH",
        "name": "Growth",
        "tagline": "FOR ESTABLISHED SELLERS",
        "description": "Full brand ownership and tools to maximize conversions.",
        "monthlyPricePesewas": 44900,
        "annualDiscountPercent": 15,
        "annualPricePesewas": 457980,
        "annualMonthlyEquivalentPesewas": 38165,
        "annualSavingPesewas": 80820,
        "currency": "GHS",
        "trialDays": 14,
        "transactionFeeBps": 100,
        "isPublic": true,
        "isDefaultForSignup": false,
        "highlighted": true,
        "badgeLabel": "Most popular",
        "bulletsHeading": "Everything in Starter, plus:",
        "marketingBullets": [
          "Custom domain connection (yourbrand.com)",
          "Up to 1,000 products & inventory management",
          "Full storefront customisation (theme & layout editor)",
          "Shop-wide discounts, returns & refunds",
          "Tax configuration & sales reports",
          "Walk-in point of sale",
          "Lower platform transaction fees"
        ],
        "sortOrder": 20,
        "active": true,
        "updatedAt": "2026-11-27T12:00:00.5Z",
        "entitlements": [
          {"featureKey": "CUSTOM_DOMAIN", "enabled": true, "limitValue": null},
          {"featureKey": "THEME_ADVANCED", "enabled": true, "limitValue": null},
          {"featureKey": "MAX_PRODUCTS", "enabled": true, "limitValue": 1000},
          {"featureKey": "PRODUCT_FAMILIES", "enabled": true, "limitValue": null},
          {"featureKey": "WALK_IN_POS", "enabled": false, "limitValue": null},
          {"featureKey": "RETURNS_REFUNDS", "enabled": true, "limitValue": null},
          {"featureKey": "SHOP_WIDE_DISCOUNTS", "enabled": true, "limitValue": null},
          {"featureKey": "MAX_ACTIVE_COUPONS", "enabled": true, "limitValue": null},
          {"featureKey": "INVENTORY_MANAGEMENT", "enabled": true, "limitValue": null},
          {"featureKey": "STOCK_TRANSFERS", "enabled": false, "limitValue": null},
          {"featureKey": "STOCK_CONVERSION", "enabled": false, "limitValue": null},
          {"featureKey": "MAX_LOCATIONS", "enabled": true, "limitValue": 1},
          {"featureKey": "SALES_REPORTS", "enabled": true, "limitValue": null},
          {"featureKey": "TAX_CONFIG", "enabled": true, "limitValue": null},
          {"featureKey": "ACCOUNTING", "enabled": false, "limitValue": null},
          {"featureKey": "MAX_STAFF", "enabled": true, "limitValue": 3},
          {"featureKey": "LOGIN_OTP", "enabled": true, "limitValue": null}
        ]
      },
      {
        "id": 3,
        "code": "SCALE",
        "name": "Scale",
        "tagline": "FOR HIGH-VOLUME BRANDS",
        "description": "Advanced operations, team access, and lowest fees.",
        "monthlyPricePesewas": 99900,
        "annualDiscountPercent": 20,
        "annualPricePesewas": 959040,
        "annualMonthlyEquivalentPesewas": 79920,
        "annualSavingPesewas": 239760,
        "currency": "GHS",
        "trialDays": 14,
        "transactionFeeBps": 50,
        "isPublic": true,
        "isDefaultForSignup": false,
        "highlighted": false,
        "badgeLabel": null,
        "bulletsHeading": "Everything in Growth, plus:",
        "marketingBullets": [
          "Unlimited products, staff and storage locations",
          "Stock transfers & stock conversion",
          "Accounting & bookkeeping",
          "Lowest transaction fee tier"
        ],
        "sortOrder": 30,
        "active": true,
        "updatedAt": "2026-11-27T09:00:00Z",
        "entitlements": [
          {"featureKey": "CUSTOM_DOMAIN", "enabled": true, "limitValue": null},
          {"featureKey": "THEME_ADVANCED", "enabled": true, "limitValue": null},
          {"featureKey": "MAX_PRODUCTS", "enabled": true, "limitValue": null},
          {"featureKey": "PRODUCT_FAMILIES", "enabled": true, "limitValue": null},
          {"featureKey": "WALK_IN_POS", "enabled": true, "limitValue": null},
          {"featureKey": "RETURNS_REFUNDS", "enabled": true, "limitValue": null},
          {"featureKey": "SHOP_WIDE_DISCOUNTS", "enabled": true, "limitValue": null},
          {"featureKey": "MAX_ACTIVE_COUPONS", "enabled": true, "limitValue": null},
          {"featureKey": "INVENTORY_MANAGEMENT", "enabled": true, "limitValue": null},
          {"featureKey": "STOCK_TRANSFERS", "enabled": true, "limitValue": null},
          {"featureKey": "STOCK_CONVERSION", "enabled": true, "limitValue": null},
          {"featureKey": "MAX_LOCATIONS", "enabled": true, "limitValue": null},
          {"featureKey": "SALES_REPORTS", "enabled": true, "limitValue": null},
          {"featureKey": "TAX_CONFIG", "enabled": true, "limitValue": null},
          {"featureKey": "ACCOUNTING", "enabled": true, "limitValue": null},
          {"featureKey": "MAX_STAFF", "enabled": true, "limitValue": null},
          {"featureKey": "LOGIN_OTP", "enabled": true, "limitValue": null}
        ]
      }
    ],
    "features": [
      {"key": "CUSTOM_DOMAIN", "kind": "BOOLEAN", "label": "Custom domain", "group": "Storefront"},
      {
        "key": "THEME_ADVANCED",
        "kind": "BOOLEAN",
        "label": "Advanced theme customisation",
        "group": "Storefront"
      },
      {"key": "MAX_PRODUCTS", "kind": "LIMIT", "label": "Products", "group": "Catalog"},
      {
        "key": "PRODUCT_FAMILIES",
        "kind": "BOOLEAN",
        "label": "Product families and pack sizes",
        "group": "Catalog"
      },
      {"key": "WALK_IN_POS", "kind": "BOOLEAN", "label": "Walk-in point of sale", "group": "Orders"},
      {"key": "RETURNS_REFUNDS", "kind": "BOOLEAN", "label": "Returns and refunds", "group": "Orders"},
      {"key": "SHOP_WIDE_DISCOUNTS", "kind": "BOOLEAN", "label": "Shop-wide discounts", "group": "Marketing"},
      {"key": "MAX_ACTIVE_COUPONS", "kind": "LIMIT", "label": "Active coupons", "group": "Marketing"},
      {
        "key": "INVENTORY_MANAGEMENT",
        "kind": "BOOLEAN",
        "label": "Inventory adjustments and history",
        "group": "Inventory"
      },
      {
        "key": "STOCK_TRANSFERS",
        "kind": "BOOLEAN",
        "label": "Stock transfers between locations",
        "group": "Inventory"
      },
      {"key": "STOCK_CONVERSION", "kind": "BOOLEAN", "label": "Stock conversion", "group": "Inventory"},
      {"key": "MAX_LOCATIONS", "kind": "LIMIT", "label": "Storage locations", "group": "Inventory"},
      {"key": "SALES_REPORTS", "kind": "BOOLEAN", "label": "Sales reports", "group": "Finance and reports"},
      {"key": "TAX_CONFIG", "kind": "BOOLEAN", "label": "Tax configuration", "group": "Finance and reports"},
      {
        "key": "ACCOUNTING",
        "kind": "BOOLEAN",
        "label": "Accounting and bookkeeping",
        "group": "Finance and reports"
      },
      {
        "key": "MAX_STAFF",
        "kind": "LIMIT",
        "label": "Front-desk staff accounts",
        "group": "Team and security"
      },
      {"key": "LOGIN_OTP", "kind": "BOOLEAN", "label": "Login one-time codes", "group": "Team and security"}
    ]
  }
}
```

## 13. `GET /api/v1/admin/subscription` (tenant)

Tenant admin route: `hasAnyRole('ADMIN','FRONT_DESK')`, the tenant is the request's resolved tenant (`TenantContext`),
never a parameter, so only the caller's own tenant is ever returned. Anonymous, foreign-tenant and platform tokens: `401`;
`CUSTOMER`: `403`; suspended tenant: `423` (tenant resolution). Never gated by a plan, also answers when `readOnly`.

`data`:

| Key | Meaning |
|---|---|
| `plan` | the tenant's plan in the public shape (also when the plan is inactive or not public) |
| `status` | `TRIALING`, `ACTIVE`, `PAST_DUE`, `CANCELED` (stored subscription status) |
| `trialEndsAt` | timestamp or `null` (the trial clock starts when provisioning completes, PLAN-02 AC9) |
| `readOnly` | `true` when `status` is `CANCELED`, or `TRIALING` with `trialEndsAt` not null and `now >= trialEndsAt` (at the instant itself the trial has ended). `ACTIVE` and `PAST_DUE` are never read-only (PAST_DUE grace is PLAN-06). Evaluated per request against the server clock |
| `entitlements` | the plan's entitlements (same list as `plan.entitlements`; kept at top level for the clients' convenience) |
| `usage` | one entry per LIMIT feature, catalog order: `{ "featureKey", "used", "limit" }`, `limit` `null` = unlimited, counted as section 15 |
| `features` | the catalog, for labels and groups |

The frontend computes "N days left" from `trialEndsAt` (display only) and shows the read-only banner from `readOnly`.

<!-- example: subscription-trialing response SubscriptionEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "plan": {
      "id": 2,
      "code": "GROWTH",
      "name": "Growth",
      "tagline": "FOR ESTABLISHED SELLERS",
      "description": "Full brand ownership and tools to maximize conversions.",
      "monthlyPricePesewas": 39900,
      "annualDiscountPercent": 20,
      "annualPricePesewas": 383040,
      "annualMonthlyEquivalentPesewas": 31920,
      "annualSavingPesewas": 95760,
      "currency": "GHS",
      "trialDays": 14,
      "transactionFeeBps": 100,
      "isPublic": true,
      "isDefaultForSignup": false,
      "highlighted": true,
      "badgeLabel": "Most popular",
      "bulletsHeading": "Everything in Starter, plus:",
      "marketingBullets": [
        "Custom domain connection (yourbrand.com)",
        "Up to 1,000 products & inventory management",
        "Full storefront customisation (theme & layout editor)",
        "Shop-wide discounts, returns & refunds",
        "Tax configuration & sales reports",
        "Walk-in point of sale",
        "Lower platform transaction fees"
      ],
      "sortOrder": 20,
      "active": true,
      "updatedAt": "2026-11-27T09:45:00Z",
      "entitlements": [
        {"featureKey": "CUSTOM_DOMAIN", "enabled": true, "limitValue": null},
        {"featureKey": "THEME_ADVANCED", "enabled": true, "limitValue": null},
        {"featureKey": "MAX_PRODUCTS", "enabled": true, "limitValue": 1000},
        {"featureKey": "PRODUCT_FAMILIES", "enabled": true, "limitValue": null},
        {"featureKey": "WALK_IN_POS", "enabled": true, "limitValue": null},
        {"featureKey": "RETURNS_REFUNDS", "enabled": true, "limitValue": null},
        {"featureKey": "SHOP_WIDE_DISCOUNTS", "enabled": true, "limitValue": null},
        {"featureKey": "MAX_ACTIVE_COUPONS", "enabled": true, "limitValue": null},
        {"featureKey": "INVENTORY_MANAGEMENT", "enabled": true, "limitValue": null},
        {"featureKey": "STOCK_TRANSFERS", "enabled": false, "limitValue": null},
        {"featureKey": "STOCK_CONVERSION", "enabled": false, "limitValue": null},
        {"featureKey": "MAX_LOCATIONS", "enabled": true, "limitValue": 1},
        {"featureKey": "SALES_REPORTS", "enabled": true, "limitValue": null},
        {"featureKey": "TAX_CONFIG", "enabled": true, "limitValue": null},
        {"featureKey": "ACCOUNTING", "enabled": false, "limitValue": null},
        {"featureKey": "MAX_STAFF", "enabled": true, "limitValue": 3},
        {"featureKey": "LOGIN_OTP", "enabled": true, "limitValue": null}
      ]
    },
    "status": "TRIALING",
    "trialEndsAt": "2026-12-11T09:30:00Z",
    "readOnly": false,
    "entitlements": [
      {"featureKey": "CUSTOM_DOMAIN", "enabled": true, "limitValue": null},
      {"featureKey": "THEME_ADVANCED", "enabled": true, "limitValue": null},
      {"featureKey": "MAX_PRODUCTS", "enabled": true, "limitValue": 1000},
      {"featureKey": "PRODUCT_FAMILIES", "enabled": true, "limitValue": null},
      {"featureKey": "WALK_IN_POS", "enabled": true, "limitValue": null},
      {"featureKey": "RETURNS_REFUNDS", "enabled": true, "limitValue": null},
      {"featureKey": "SHOP_WIDE_DISCOUNTS", "enabled": true, "limitValue": null},
      {"featureKey": "MAX_ACTIVE_COUPONS", "enabled": true, "limitValue": null},
      {"featureKey": "INVENTORY_MANAGEMENT", "enabled": true, "limitValue": null},
      {"featureKey": "STOCK_TRANSFERS", "enabled": false, "limitValue": null},
      {"featureKey": "STOCK_CONVERSION", "enabled": false, "limitValue": null},
      {"featureKey": "MAX_LOCATIONS", "enabled": true, "limitValue": 1},
      {"featureKey": "SALES_REPORTS", "enabled": true, "limitValue": null},
      {"featureKey": "TAX_CONFIG", "enabled": true, "limitValue": null},
      {"featureKey": "ACCOUNTING", "enabled": false, "limitValue": null},
      {"featureKey": "MAX_STAFF", "enabled": true, "limitValue": 3},
      {"featureKey": "LOGIN_OTP", "enabled": true, "limitValue": null}
    ],
    "usage": [
      {"featureKey": "MAX_PRODUCTS", "used": 32, "limit": 1000},
      {"featureKey": "MAX_ACTIVE_COUPONS", "used": 4, "limit": null},
      {"featureKey": "MAX_LOCATIONS", "used": 1, "limit": 1},
      {"featureKey": "MAX_STAFF", "used": 2, "limit": 3}
    ],
    "features": [
      {"key": "CUSTOM_DOMAIN", "kind": "BOOLEAN", "label": "Custom domain", "group": "Storefront"},
      {
        "key": "THEME_ADVANCED",
        "kind": "BOOLEAN",
        "label": "Advanced theme customisation",
        "group": "Storefront"
      },
      {"key": "MAX_PRODUCTS", "kind": "LIMIT", "label": "Products", "group": "Catalog"},
      {
        "key": "PRODUCT_FAMILIES",
        "kind": "BOOLEAN",
        "label": "Product families and pack sizes",
        "group": "Catalog"
      },
      {"key": "WALK_IN_POS", "kind": "BOOLEAN", "label": "Walk-in point of sale", "group": "Orders"},
      {"key": "RETURNS_REFUNDS", "kind": "BOOLEAN", "label": "Returns and refunds", "group": "Orders"},
      {"key": "SHOP_WIDE_DISCOUNTS", "kind": "BOOLEAN", "label": "Shop-wide discounts", "group": "Marketing"},
      {"key": "MAX_ACTIVE_COUPONS", "kind": "LIMIT", "label": "Active coupons", "group": "Marketing"},
      {
        "key": "INVENTORY_MANAGEMENT",
        "kind": "BOOLEAN",
        "label": "Inventory adjustments and history",
        "group": "Inventory"
      },
      {
        "key": "STOCK_TRANSFERS",
        "kind": "BOOLEAN",
        "label": "Stock transfers between locations",
        "group": "Inventory"
      },
      {"key": "STOCK_CONVERSION", "kind": "BOOLEAN", "label": "Stock conversion", "group": "Inventory"},
      {"key": "MAX_LOCATIONS", "kind": "LIMIT", "label": "Storage locations", "group": "Inventory"},
      {"key": "SALES_REPORTS", "kind": "BOOLEAN", "label": "Sales reports", "group": "Finance and reports"},
      {"key": "TAX_CONFIG", "kind": "BOOLEAN", "label": "Tax configuration", "group": "Finance and reports"},
      {
        "key": "ACCOUNTING",
        "kind": "BOOLEAN",
        "label": "Accounting and bookkeeping",
        "group": "Finance and reports"
      },
      {
        "key": "MAX_STAFF",
        "kind": "LIMIT",
        "label": "Front-desk staff accounts",
        "group": "Team and security"
      },
      {"key": "LOGIN_OTP", "kind": "BOOLEAN", "label": "Login one-time codes", "group": "Team and security"}
    ]
  }
}
```

A Starter tenant whose trial ended (product count is at the limit; reads keep working, gated writes answer 402):

<!-- example: subscription-read-only response SubscriptionEnvelope 200 -->
```json
{
  "description": null,
  "data": {
    "plan": {
      "id": 1,
      "code": "STARTER",
      "name": "Starter",
      "tagline": "FOR YOUR FIRST SALES",
      "description": "Everything you need to launch your first online store.",
      "monthlyPricePesewas": 14900,
      "annualDiscountPercent": 20,
      "annualPricePesewas": 143040,
      "annualMonthlyEquivalentPesewas": 11920,
      "annualSavingPesewas": 35760,
      "currency": "GHS",
      "trialDays": 14,
      "transactionFeeBps": 150,
      "isPublic": true,
      "isDefaultForSignup": true,
      "highlighted": false,
      "badgeLabel": null,
      "bulletsHeading": "Your launch essentials",
      "marketingBullets": [
        "Free platform subdomain (yourstore.shopfi.store)",
        "Up to 50 products",
        "Integrated online checkout & payment processing",
        "Up to 3 active coupon codes",
        "Standard storefront templates"
      ],
      "sortOrder": 10,
      "active": true,
      "updatedAt": "2026-11-27T09:40:00Z",
      "entitlements": [
        {"featureKey": "CUSTOM_DOMAIN", "enabled": false, "limitValue": null},
        {"featureKey": "THEME_ADVANCED", "enabled": false, "limitValue": null},
        {"featureKey": "MAX_PRODUCTS", "enabled": true, "limitValue": 50},
        {"featureKey": "PRODUCT_FAMILIES", "enabled": false, "limitValue": null},
        {"featureKey": "WALK_IN_POS", "enabled": false, "limitValue": null},
        {"featureKey": "RETURNS_REFUNDS", "enabled": false, "limitValue": null},
        {"featureKey": "SHOP_WIDE_DISCOUNTS", "enabled": false, "limitValue": null},
        {"featureKey": "MAX_ACTIVE_COUPONS", "enabled": true, "limitValue": 3},
        {"featureKey": "INVENTORY_MANAGEMENT", "enabled": false, "limitValue": null},
        {"featureKey": "STOCK_TRANSFERS", "enabled": false, "limitValue": null},
        {"featureKey": "STOCK_CONVERSION", "enabled": false, "limitValue": null},
        {"featureKey": "MAX_LOCATIONS", "enabled": true, "limitValue": 1},
        {"featureKey": "SALES_REPORTS", "enabled": false, "limitValue": null},
        {"featureKey": "TAX_CONFIG", "enabled": false, "limitValue": null},
        {"featureKey": "ACCOUNTING", "enabled": false, "limitValue": null},
        {"featureKey": "MAX_STAFF", "enabled": false, "limitValue": 0},
        {"featureKey": "LOGIN_OTP", "enabled": false, "limitValue": null}
      ]
    },
    "status": "TRIALING",
    "trialEndsAt": "2026-11-20T08:00:00Z",
    "readOnly": true,
    "entitlements": [
      {"featureKey": "CUSTOM_DOMAIN", "enabled": false, "limitValue": null},
      {"featureKey": "THEME_ADVANCED", "enabled": false, "limitValue": null},
      {"featureKey": "MAX_PRODUCTS", "enabled": true, "limitValue": 50},
      {"featureKey": "PRODUCT_FAMILIES", "enabled": false, "limitValue": null},
      {"featureKey": "WALK_IN_POS", "enabled": false, "limitValue": null},
      {"featureKey": "RETURNS_REFUNDS", "enabled": false, "limitValue": null},
      {"featureKey": "SHOP_WIDE_DISCOUNTS", "enabled": false, "limitValue": null},
      {"featureKey": "MAX_ACTIVE_COUPONS", "enabled": true, "limitValue": 3},
      {"featureKey": "INVENTORY_MANAGEMENT", "enabled": false, "limitValue": null},
      {"featureKey": "STOCK_TRANSFERS", "enabled": false, "limitValue": null},
      {"featureKey": "STOCK_CONVERSION", "enabled": false, "limitValue": null},
      {"featureKey": "MAX_LOCATIONS", "enabled": true, "limitValue": 1},
      {"featureKey": "SALES_REPORTS", "enabled": false, "limitValue": null},
      {"featureKey": "TAX_CONFIG", "enabled": false, "limitValue": null},
      {"featureKey": "ACCOUNTING", "enabled": false, "limitValue": null},
      {"featureKey": "MAX_STAFF", "enabled": false, "limitValue": 0},
      {"featureKey": "LOGIN_OTP", "enabled": false, "limitValue": null}
    ],
    "usage": [
      {"featureKey": "MAX_PRODUCTS", "used": 50, "limit": 50},
      {"featureKey": "MAX_ACTIVE_COUPONS", "used": 1, "limit": 3},
      {"featureKey": "MAX_LOCATIONS", "used": 1, "limit": 1},
      {"featureKey": "MAX_STAFF", "used": 0, "limit": 0}
    ],
    "features": [
      {"key": "CUSTOM_DOMAIN", "kind": "BOOLEAN", "label": "Custom domain", "group": "Storefront"},
      {
        "key": "THEME_ADVANCED",
        "kind": "BOOLEAN",
        "label": "Advanced theme customisation",
        "group": "Storefront"
      },
      {"key": "MAX_PRODUCTS", "kind": "LIMIT", "label": "Products", "group": "Catalog"},
      {
        "key": "PRODUCT_FAMILIES",
        "kind": "BOOLEAN",
        "label": "Product families and pack sizes",
        "group": "Catalog"
      },
      {"key": "WALK_IN_POS", "kind": "BOOLEAN", "label": "Walk-in point of sale", "group": "Orders"},
      {"key": "RETURNS_REFUNDS", "kind": "BOOLEAN", "label": "Returns and refunds", "group": "Orders"},
      {"key": "SHOP_WIDE_DISCOUNTS", "kind": "BOOLEAN", "label": "Shop-wide discounts", "group": "Marketing"},
      {"key": "MAX_ACTIVE_COUPONS", "kind": "LIMIT", "label": "Active coupons", "group": "Marketing"},
      {
        "key": "INVENTORY_MANAGEMENT",
        "kind": "BOOLEAN",
        "label": "Inventory adjustments and history",
        "group": "Inventory"
      },
      {
        "key": "STOCK_TRANSFERS",
        "kind": "BOOLEAN",
        "label": "Stock transfers between locations",
        "group": "Inventory"
      },
      {"key": "STOCK_CONVERSION", "kind": "BOOLEAN", "label": "Stock conversion", "group": "Inventory"},
      {"key": "MAX_LOCATIONS", "kind": "LIMIT", "label": "Storage locations", "group": "Inventory"},
      {"key": "SALES_REPORTS", "kind": "BOOLEAN", "label": "Sales reports", "group": "Finance and reports"},
      {"key": "TAX_CONFIG", "kind": "BOOLEAN", "label": "Tax configuration", "group": "Finance and reports"},
      {
        "key": "ACCOUNTING",
        "kind": "BOOLEAN",
        "label": "Accounting and bookkeeping",
        "group": "Finance and reports"
      },
      {
        "key": "MAX_STAFF",
        "kind": "LIMIT",
        "label": "Front-desk staff accounts",
        "group": "Team and security"
      },
      {"key": "LOGIN_OTP", "kind": "BOOLEAN", "label": "Login one-time codes", "group": "Team and security"}
    ]
  }
}
```

## 14. 402 Payment Required

A tenant route that needs a feature the tenant's plan lacks, or a write while the subscription is inactive, answers
`402` with an `ApiCodeException` body. The frontend opens ONE upgrade modal for any 402 from any admin call, keyed on
`data.code` and `data.reason` (PLAN-05 AC9); it never shows a generic error for it.

### 14.1 `PLAN_UPGRADE_REQUIRED`

`data = { "code": "PLAN_UPGRADE_REQUIRED", "feature": <key>, "reason": "NOT_IN_PLAN" | "SUBSCRIPTION_INACTIVE", "requiredPlans": [<codes>] }`

| `reason` | When | `description` (exact) |
|---|---|---|
| `NOT_IN_PLAN` | the route's BOOLEAN feature is off in the tenant's plan (any method, reads included) | `Your plan does not include this feature.` |
| `SUBSCRIPTION_INACTIVE` | `readOnly` is true (13) and the request is a gated write (any method other than GET/HEAD on a gated route) or a limit-checked create; `feature` is the route's feature, or the LIMIT key for a create | `Your subscription is not active. Existing data stays readable; upgrade to make changes.` |

<!-- example: upgrade-required-not-in-plan response PlanUpgradeRequiredEnvelope 402 -->
```json
{
  "description": "Your plan does not include this feature.",
  "data": { "code": "PLAN_UPGRADE_REQUIRED", "feature": "WALK_IN_POS", "reason": "NOT_IN_PLAN", "requiredPlans": ["GROWTH", "SCALE"] }
}
```

<!-- example: upgrade-required-inactive response PlanUpgradeRequiredEnvelope 402 -->
```json
{
  "description": "Your subscription is not active. Existing data stays readable; upgrade to make changes.",
  "data": { "code": "PLAN_UPGRADE_REQUIRED", "feature": "MAX_PRODUCTS", "reason": "SUBSCRIPTION_INACTIVE", "requiredPlans": ["GROWTH", "SCALE"] }
}
```

### 14.2 `PLAN_LIMIT_REACHED`

`data = { "code": "PLAN_LIMIT_REACHED", "feature": <LIMIT key>, "limit": <int>, "used": <int>, "requiredPlans": [<codes>] }`,
`description` exactly `You have reached your plan's limit for this feature.` Answered by a limit-checked create when
`used >= limit` (section 15). `limit - 1` used: the create succeeds. `limit` `null` (unlimited) never answers this; `0`
answers it on the first create. The UI shows "You have used `used` of `limit`".

<!-- example: limit-reached response PlanLimitReachedEnvelope 402 -->
```json
{
  "description": "You have reached your plan's limit for this feature.",
  "data": { "code": "PLAN_LIMIT_REACHED", "feature": "MAX_PRODUCTS", "limit": 50, "used": 50, "requiredPlans": ["GROWTH", "SCALE"] }
}
```

### 14.3 `requiredPlans`

Codes of the plans that are `isPublic` and `active` and would allow the action, in `sortOrder` then `id`:

- BOOLEAN feature: plans where it is enabled (the tenant's own plan is listed too when it has the feature, e.g. for
  `SUBSCRIPTION_INACTIVE`);
- LIMIT feature: plans whose limit is `null` or greater than the tenant's current `used` (for `SUBSCRIPTION_INACTIVE` on a
  create: the same rule).

It may be **empty** (no public plan offers it): the modal then shows "contact us" instead of plan names. Codes, not
names: the client maps them to names with the public plans list (12). Non-public or inactive plans are never named.

### 14.4 Precedence

Checks run in this order, the first failure answers:

1. Tenant resolution (`TenantResolutionFilter`, before authentication): no tenant `400`, unknown tenant `404`,
   **suspended tenant `423`**. These come first for every caller, signed in or not (existing behaviour, errors.md).
2. Authentication: `401`.
3. Authorization: `403` (role matcher, `@PreAuthorize`, and the front-desk permission checks). A FRONT_DESK user without
   the permission gets `403`, never a `402` that reveals the plan.
4. Plan gate: `402 PLAN_UPGRADE_REQUIRED` (`NOT_IN_PLAN` before `SUBSCRIPTION_INACTIVE`).
5. Request validation: `400` (bean validation and body parsing) on feature-gated routes; a gated route never reveals
   field errors to a tenant whose plan lacks the feature.
6. Limit check inside the create service: `402 PLAN_UPGRADE_REQUIRED` (`SUBSCRIPTION_INACTIVE`) then
   `402 PLAN_LIMIT_REACHED`. Limit checks run in the service, after the body was parsed and validated, so an invalid
   create body at the limit gets the `400`.

The board AC (PLAN-03 AC3) wrote the order as 401 -> 403 -> 423 -> 402 -> 400; in this code base the 423 is produced by
the tenant-resolution filter before authentication runs, so it precedes 401 (step 1). Implementation note for PLAN-02:
Spring calls a `HandlerInterceptor.preHandle` BEFORE `@PreAuthorize` method security and before `@Valid` body binding,
so a plain interceptor would answer 402 where step 3 must answer 403 for routes whose role is only in `@PreAuthorize`
(e.g. FRONT_DESK on `/admin/accounting/**`) and for service-level permission checks. The gate must evaluate after
authorization (and the PLAN-02 tests pin step 3 before step 4 for every gated route).

## 15. Semantics (what a change does)

- **Entitlement edits** apply on the next request (after commit) for every tenant on the plan; no restart, no re-login.
- **Lowering a limit** below a tenant's usage keeps all existing data readable, editable, deletable and deactivatable;
  only new creates answer `402 PLAN_LIMIT_REACHED` until usage is below the limit again. Turning a BOOLEAN off keeps the
  data; the gated routes answer 402 (reads included) until the feature is back.
- **Trial length** (`trialDays`) affects only trials that start after the change; existing `trialEndsAt` values are not
  touched.
- **Price or annual-discount changes** affect only the next billing cycle (there is no billing until PLAN-06); the
  derived annual fields change at once in every response.
- **Trial expired or `CANCELED`** (`readOnly`): reads allowed (GET/HEAD on gated routes of features in the plan), every
  gated write and every limit-checked create answers `402` reason `SUBSCRIPTION_INACTIVE`; core features (orders,
  checkout, storefront, products other than create, ...) are unaffected. The super-admin keeps a tenant going by
  extending the trial or setting it `ACTIVE` (11).
- **Sign-up** (O1): every new tenant gets the `isDefaultForSignup` plan, `subscriptionStatus` `TRIALING`, and
  `trialEndsAt` = the moment provisioning completes (tenant becomes `ACTIVE`) + that plan's `trialDays` at that moment.
  Any `plan` value in the signup body is ignored (onboarding.md). Until provisioning completes `trialEndsAt` is `null`.
- **Feature flags vs entitlements**: an entitlement decides whether the tenant MAY use a feature; the existing feature
  flags (feature-flags.md) decide whether it has turned it on. A tenant that loses `LOGIN_OTP` while the flag is on keeps
  OTP enforced (security is never weakened silently); it may turn the flag off but not back on (402 `NOT_IN_PLAN`).

How each limit is counted (the same count is `usage[].used` and the 402 `used`), in the tenant's own schema:

| Key | Counts |
|---|---|
| `MAX_PRODUCTS` | every row in `products` (products are hard-deleted; family variants are product rows and count; inactive / unlisted products count) |
| `MAX_ACTIVE_COUPONS` | coupons with `is_active = true` (an expired coupon that was never deactivated still counts) |
| `MAX_STAFF` | users with the `FRONT_DESK` role and `is_active = true` (ADMINs and customers never count) |
| `MAX_LOCATIONS` | every storage location, active or not |

Custom domains have no limit: `CUSTOM_DOMAIN` is a boolean and a tenant has at most one domain (O3). Count and insert are
race-safe: two concurrent creates at `limit - 1` let exactly one through (PLAN-02 AC4).

## 16. Guidance for frontend implementers

- **Parsers**: validate every response against this document (PLAN-04 AC9, PLAN-05 AC12). Reject (do not render) a plan
  whose derived fields are missing, a feature with an unknown `kind`, an entitlement whose key is not in `features`, a
  LIMIT entitlement with a negative limit. Unknown extra keys may be ignored.
- **Money**: integer pesewas end to end; format only at display ("GHS 149", "GHS 1,430.40"). The annual numbers come from
  the API; while a price or percent is being edited in the console, show "recalculated on save" instead of computing.
- **Console writes**: always send the `version` you read; on `409 PLAN_VERSION_CONFLICT` keep the edits and let the user
  reload or review (9). On `400` map `data[<field path>]` to the field. Matrix saves replace the whole client state with
  the response.
- **402**: one modal for any admin call; `PLAN_LIMIT_REACHED` -> "You have used X of Y"; `SUBSCRIPTION_INACTIVE` -> the
  read-only explanation; `requiredPlans` empty -> "contact us". A side panel that calls a gated endpoint (product detail
  sales panel) shows a locked panel, not an error page.
- **Locks before the click**: the shared subscription hook (`GET /admin/subscription`) drives "Upgrade" badges; the
  402 stays the authority (the plan can change between the read and the click).

## 17. Open points for the PO (defaults chosen in this contract)

These are defaults this document picked where the AC were silent; change them here before PLAN-01/02 start if needed.

1. `trialEndsAt` "extend only" read literally: later than the current trial end; it revives `CANCELED` to `TRIALING`
   and is refused for `ACTIVE` / `PAST_DUE` tenants (11).
2. `isDefaultForSignup: true` on another plan moves the flag (bumping the other plan's version) rather than requiring
   two steps (7).
3. Expired-but-active coupons count towards `MAX_ACTIVE_COUPONS`; inactive storage locations count towards
   `MAX_LOCATIONS` (AC6 says "all") (15).
4. Field limits the AC did not state: `name` <= 40, `bulletsHeading` <= 80, `monthlyPricePesewas` <= 100,000,000,
   `sortOrder` 0..1000, matrix <= 50 items (3.1, 8).
5. Tenant audit rows for a plan change use the new action `CHANGE_PLAN` with `previousStatus` = `newStatus` (the V6
   CHECK and the console's audit parser must accept it) (11).
6. Precedence of 423 before 401 (14.4) and of limit-check 400 before 402: forced by the existing filter chain and
   service-level limit checks; the AC order is kept for everything else.
