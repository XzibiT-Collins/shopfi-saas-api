# Discounts — Frontend Integration Manual

Audience: the frontend agent integrating product-specific and shop-wide discounts into the **already-live** storefront + admin app.

This document is the contract. It states exactly what the backend now returns, the new admin endpoints, the rules the backend enforces (so the UI can pre-empt errors), and a per-screen task list.

---

## 1. What changed and why

The backend gained two discount mechanisms, both time-boxed:

1. **Product-specific discount** — a single product on sale for a period. Either `PERCENTAGE` off or a `FLAT` amount off. Reverts to original price automatically when the window ends.
2. **Shop-wide discount** — a store-wide **percentage** applied to every product for a period.

Precedence and stacking rules (enforced server-side — the UI must mirror them to avoid failed requests):

- A product's own discount **wins** over the shop-wide discount for that product.
- Shop-wide is **percentage only** (no flat shop-wide discount).
- **No coupon stacking.** If any item in the cart is currently discounted, the existing checkout **coupon is rejected**.
- **Walk-in / POS:** discounts apply at the counter too, and the existing **manual order discount is rejected** when any line item is on sale.
- Discounts are resolved dynamically by date window. No "activate/deactivate" toggle is needed for expiry — once `endAt` passes, prices revert on their own.

Nothing else about pricing semantics changed. The big difference for the frontend: **price fields now carry the discounted (effective) price, and there are new fields for the original price, sale flag, percentage, and end date.**

---

## 2. Response envelope (unchanged, for reference)

Every endpoint wraps payloads in:

```json
{ "description": null, "data": <payload> }
```

Paginated endpoints put a `PageResponse` in `data`:

```json
{
  "description": null,
  "data": {
    "content": [ /* items */ ],
    "totalPages": 3, "totalElements": 25, "size": 10, "number": 0,
    "numberOfElements": 10, "isFirst": true, "isLast": false, "isEmpty": false
  }
}
```

---

## 3. New fields on customer-facing product responses

Three response shapes gained the **same four new fields**. Critical change in meaning:

- `price` / `sellingPrice` now = the **effective (discounted) price the customer pays**. Existing UI that renders this field keeps working — it just shows the sale price when on sale.
- `originalPrice` = pre-discount price. Equal to the effective price when **not** on sale.

### New fields (identical across the three DTOs)

| Field | Type | Notes |
|---|---|---|
| `originalPrice` | string | Money string, e.g. `"GHS 120.00"`. Render struck-through when `onSale`. |
| `onSale` | boolean | `true` when an active discount applies. Drive all sale UI off this. |
| `discountPercentage` | number | Percent off, 2dp, e.g. `20.00`. **Derived** even for `FLAT` discounts (so you can always show "X% off"). `0` when not on sale. |
| `discountEndsAt` | string \| null | Discount window end. `null` when not on sale. Same timestamp format as existing fields (e.g. order `createdAt`). Use for countdowns. |

> **Money string format:** all price fields are strings shaped `"<CURRENCY> <AMOUNT>"`, e.g. `"GHS 49.99"`. This is unchanged from today. If the UI already parses `price`, reuse that parser for `originalPrice`.

### 3.1 `ProductListing` — used by listing, search, featured

`GET /api/v1/product/listing` (paged), `GET /api/v1/product/search` (paged), `GET /api/v1/product/featured` (list).

```json
{
  "productId": 12,
  "productName": "Perfume A",
  "productShortDescription": "...",
  "productImageUrl": "https://...",
  "categoryName": "Fragrance",
  "price": "GHS 96.00",
  "originalPrice": "GHS 120.00",
  "onSale": true,
  "discountPercentage": 20.00,
  "discountEndsAt": "2026-06-15T23:59:00",
  "isOutOfStock": false,
  "isActive": true,
  "isEnlisted": true,
  "slug": "perfume-a",
  "stockQuantity": 8
}
```

### 3.2 `ProductDetailsPageResponse` — customer product detail page

`GET /api/v1/product/{slug}`

```json
{
  "productId": 12,
  "productName": "Perfume A",
  "productShortDescription": "...",
  "productDescription": "...",
  "productImageUrl": "https://...",
  "category": "Fragrance",
  "sellingPrice": "GHS 96.00",
  "originalPrice": "GHS 120.00",
  "onSale": true,
  "discountPercentage": 20.00,
  "discountEndsAt": "2026-06-15T23:59:00",
  "isOutOfStock": false,
  "isFeatured": true,
  "slug": "perfume-a"
}
```

### 3.3 `ProductDetails` — admin product view + create/update/discount responses

`GET /api/v1/product/main/{productId}` and returned by create/update/set-discount/clear-discount.

Same four new fields, alongside existing `sellingPrice`, `costPrice`, `stockKeepingUnit`, etc. `sellingPrice` = effective; `originalPrice` = pre-discount.

### 3.4 Cart responses — `CartResponse` / `CartItemResponse`

Returned by every cart endpoint:
- `GET /api/v1/cart` — get cart (`CartResponse`)
- `POST /api/v1/cart/populate` — bulk add from local storage (`CartResponse`)
- `POST /api/v1/cart/items/add-item` — add item (`CartItemResponse`)
- `PUT /api/v1/cart/items/update/{cartItemId}` — update qty (`CartItemResponse`)

**The cart now reflects discounts** — previously it showed base price. Per-unit and totals carry the effective (discounted) value, with originals for strike-through.

`CartItemResponse`:

```json
{
  "cartItemId": 5,
  "productId": 12,
  "productName": "Perfume A",
  "productImageUrl": "https://...",
  "unitPrice": "GHS 96.00",          // effective per-unit price
  "originalUnitPrice": "GHS 120.00", // pre-discount per-unit (== unitPrice when not on sale)
  "onSale": true,
  "discountPercentage": 20.00,
  "quantity": 2
}
```

`CartResponse`:

```json
{
  "cartItems": [ /* CartItemResponse[] */ ],
  "totalPrice": "GHS 192.00",         // effective total (what will be charged)
  "originalTotalPrice": "GHS 240.00"  // pre-discount total (== totalPrice when nothing on sale)
}
```

Frontend tasks:
- Render `unitPrice` as the price; show `originalUnitPrice` struck-through and a `{discountPercentage}% off` badge when `onSale`.
- Show `totalPrice` as the cart total; show `originalTotalPrice` struck-through when it differs (any item on sale).
- This is the source of truth for "any item on sale" → use it to disable the coupon input (see §5.1).
- Note: `unitPrice` is recomputed live (not the stale add-to-cart snapshot), so it always matches checkout.

---

### 3.5 Order & walk-in responses — discount breakdown

`OrderResponse` (online order detail) and `WalkInOrderResponse` gained two fields so receipts/order
views can show the markdown:

| Field | Meaning |
|---|---|
| `subtotal` | NET of automatic (product/shop) discount — unchanged meaning |
| `originalSubtotal` | gross, before the automatic discount |
| `automaticDiscountAmount` | product/shop discount baked into the line prices |
| `discountAmount` | coupon (online) / manual (walk-in) discount — unchanged |

All are money strings (`"GHS 100.00"`). `originalSubtotal == subtotal` when nothing was on sale.
Show "You saved {automaticDiscountAmount}" and/or a struck-through `originalSubtotal` on receipts.

> Accounting note (no frontend action): these discounts are now booked as gross revenue + a
> `DISCOUNT_EXPENSE` line, so the P&L reflects all discounts given. Net profit is unchanged.

## 4. New admin endpoints

All require `ADMIN` role (same auth/JWT as existing admin endpoints). All wrap responses in the standard envelope.

### 4.1 Set / replace a product discount

```
PUT /api/v1/product/admin/{productId}/discount
Content-Type: application/json
```

Request body (`ProductDiscountRequest`):

```json
{
  "discountType": "PERCENTAGE",     // "PERCENTAGE" | "FLAT"
  "discountValue": 20,              // percent (0–100] for PERCENTAGE; money amount for FLAT
  "startAt": "2026-06-01T00:00:00", // timestamp, no zone
  "endAt": "2026-06-15T23:59:00"
}
```

Response: `200 OK`, `data` = updated `ProductDetails` (reflects the new effective price if the window is already active).

Validation (return-as-`400` rules to mirror in the form):
- `discountType`, `discountValue`, `startAt`, `endAt` all required.
- `discountValue` must be `> 0`.
- `PERCENTAGE`: value must be `<= 100`.
- `FLAT`: value must be `< product price`.
- `endAt` must be **after** `startAt`.

### 4.2 Clear a product discount

```
DELETE /api/v1/product/admin/{productId}/discount
```

Response: `200 OK`, `data` = updated `ProductDetails` (back to original price).

### 4.3 Set / replace the shop-wide discount

```
PUT /api/v1/admin/shop-discount
Content-Type: application/json
```

Request body (`ShopWideDiscountRequest`):

```json
{
  "label": "Summer Sale",        // required, non-blank
  "discountPercentage": 10,      // (0–100]
  "startAt": "2026-06-01T00:00:00",
  "endAt": "2026-06-30T23:59:00"
}
```

Response: `200 OK`, `data` = `ShopWideDiscountResponse` (see 4.5). Setting a new one automatically retires any previously active shop-wide discount (only one applies at a time).

Validation: `label` non-blank, `discountPercentage` required and in `(0, 100]`, `endAt` after `startAt`.

### 4.4 Get current shop-wide discount

```
GET /api/v1/admin/shop-discount/current
```

- `200 OK` with `data` = `ShopWideDiscountResponse` when one is configured.
- `404` (ResourceNotFound) when none is in force right now (none configured, expired, or not started yet — API-04). Treat 404 as "no shop-wide discount" — show empty state, not an error toast.

### 4.5 Deactivate a shop-wide discount

```
DELETE /api/v1/admin/shop-discount/{id}
```

Response: `204 No Content`.

### `ShopWideDiscountResponse` shape

```json
{
  "id": 3,
  "label": "Summer Sale",
  "discountPercentage": 10.00,
  "startAt": "2026-06-01T00:00:00",
  "endAt": "2026-06-30T23:59:00",
  "isActive": true,          // configured-active flag
  "currentlyActive": true    // isActive AND now within [startAt, endAt]
}
```

> Use `currentlyActive` to show "Live now" vs "Scheduled" / "Expired" badges. `isActive` alone does not mean it is in effect right now.

---

## 5. Behavior the UI must mirror (to avoid 400s)

### 5.1 Checkout coupon vs discounts (online)

Existing checkout: `POST` checkout with optional `couponCode`. New rule:

- If **any cart item is `onSale`** and a `couponCode` is sent, backend returns `400` with description **"Coupons cannot be combined with active product discounts."**

Frontend tasks:
- When the cart contains any `onSale` item, **disable the coupon input** and show a note ("Coupon codes can't be combined with items already on sale.").
- Compute "any on sale" from `cartItems[].onSale` in the `CartResponse` (§3.4) — no extra fetch needed.
- Still handle the `400` gracefully as a fallback.

### 5.2 Walk-in / POS manual discount vs discounts

Walk-in order creation accepts a manual `discountType` + `discountValue`. New rule:

- If **any line item is on sale** and a manual discount is provided, backend returns `400` with description **"Manual discount cannot be combined with active product discounts."**

Frontend tasks (POS screen):
- After resolving line items, if any product is `onSale`, **disable the manual discount controls** and show the line-level sale prices.
- Line unit prices the customer is charged already reflect the discount; don't re-apply anything client-side.

### 5.3 Cart price freshness

Effective prices are date-windowed and the catalog is cached (up to ~60 min, plus a periodic eviction job). A discount can start/end between page loads. Recommendation:
- Re-fetch product/cart pricing at checkout entry so totals match what the backend will charge.
- Don't compute totals purely client-side from a stale `price`; trust the backend total at checkout.

---

## 6. Per-screen task list

### Storefront — product card (listing / search / featured)
- Read `onSale`. When true: show `price` as the sale price, render `originalPrice` struck-through, show a `{discountPercentage}% off` badge.
- Optional: "Ends {discountEndsAt}" label / countdown.
- When `onSale` is false: render `price` as today; ignore the other fields.

### Storefront — product detail page
- Same as card, using `sellingPrice` (effective) + `originalPrice` + `onSale` + `discountPercentage` + `discountEndsAt`.

### Storefront — cart & checkout
- Show per-line sale price where `onSale`.
- Disable coupon entry when any line is on sale (5.1). Handle the 400 fallback.
- Re-fetch pricing on checkout entry (5.3).

### Admin — product form / product table
- Add a "Discount" section/action: set discount (4.1) and clear discount (4.2).
- Form fields: type (`PERCENTAGE`/`FLAT`), value, `startAt`, `endAt`. Enforce the 4.1 validation client-side (esp. `FLAT < price`, `endAt > startAt`, percentage ≤ 100).
- In the product table, surface a sale badge using `onSale` + `discountPercentage` from `ProductListing` (admin listing endpoint).

### Admin — shop-wide discount screen (new)
- On load, `GET /current` (4.4). 404 → empty state.
- Form: `label`, `discountPercentage`, `startAt`, `endAt` → `PUT` (4.3).
- Show `currentlyActive` badge (Live / Scheduled / Expired derived from `currentlyActive` + window).
- "End discount" button → `DELETE /{id}` (4.5).

---

## 7. Quick reference

| Endpoint | Method | Auth | Body | Returns |
|---|---|---|---|---|
| `/api/v1/product/listing` | GET | public | — | `PageResponse<ProductListing>` |
| `/api/v1/product/search` | GET | public | — | `PageResponse<ProductListing>` |
| `/api/v1/product/featured` | GET | public | — | `List<ProductListing>` |
| `/api/v1/product/{slug}` | GET | public | — | `ProductDetailsPageResponse` |
| `/api/v1/product/admin/{productId}/discount` | PUT | ADMIN | `ProductDiscountRequest` | `ProductDetails` |
| `/api/v1/product/admin/{productId}/discount` | DELETE | ADMIN | — | `ProductDetails` |
| `/api/v1/admin/shop-discount` | PUT | ADMIN | `ShopWideDiscountRequest` | `ShopWideDiscountResponse` |
| `/api/v1/admin/shop-discount/current` | GET | ADMIN | — | `ShopWideDiscountResponse` (404 if none **in force now**) |
| `/api/v1/admin/shop-discount/{id}` | DELETE | ADMIN | — | `204` |

Enum values: `discountType` ∈ `{ "PERCENTAGE", "FLAT" }`.

Money strings: `"<CURRENCY> <AMOUNT>"`, e.g. `"GHS 49.99"`.

Timestamps (`startAt`, `endAt`, `discountEndsAt`): same serialization as existing API date fields (e.g. order `createdAt`) — send/parse in that same format.

---

## 8. Coupons (API-04)

All routes are `ADMIN` only (anonymous `401`, customer `403`). Envelope as in section 2.

| Route | Method | Body | Response |
|---|---|---|---|
| `/api/v1/coupon/add-coupon` | POST | `CouponRequest` | `CouponDetailResponse` (200) |
| `/api/v1/coupon/{couponId}` | GET | — | `CouponDetailResponse` |
| `/api/v1/coupon/all` | GET | `?page&size&sort` | `PageResponse<CouponListResponse>` |
| `/api/v1/coupon/{couponId}` | PUT | `CouponRequest` (full replace) | `CouponDetailResponse` |
| `/api/v1/coupon/{couponId}/deactivate` | POST | — | `CouponDetailResponse` (`isActive=false`) |
| `/api/v1/coupon/{couponId}` | DELETE | — | `204` |

### `CouponRequest`

| Field | Rule |
|---|---|
| `couponCode` | Optional (omit or `null` to have one generated). When sent: 3-32 chars of `A-Z a-z 0-9 _ -` (first char alphanumeric). It is **trimmed and upper-cased** before it is stored, so the response `couponCode` is the canonical form and checkout/preview match it in any case. Blank (`""`, spaces) is `400`. Cannot change on update (send the same code or omit it). **On `PUT`, prefer omitting it:** a code renamed by migration V12 (`<CODE>-DUP-<id>`, `LEGACY-BLANK-<id>`) or any legacy code can be longer than 32 characters or contain other characters, and sending it back then fails this format check (`400`) even though it is the coupon's own code. |
| `discountType` | `PERCENTAGE` or `FLAT`, required. |
| `discountValue` | Required, `> 0`, at most 2 decimals. `PERCENTAGE` must be `<= 100`. |
| `maximumDiscountAmount` | **Now optional.** Cap for `PERCENTAGE` coupons only (`> 0` when sent, `null` = no cap). For `FLAT` it is **ignored and stored as `null`** (a flat coupon is already a fixed amount, bounded by the cart subtotal). Clients that still send a value for FLAT keep working. |
| `minimumCartAmountForDiscount` | **Now optional**, `>= 0`, 2 decimals. |
| `usageLimit` | Required integer `1..1000000`. Must be a JSON integer: `1.5`, `"7"` (string), `true` or an out-of-range number such as `99999999999` / `1E999999999` are a `400` with `description` `"usageLimit must be a whole number"` / `"usageLimit must be a whole number (a JSON number, not text)"` / `"usageLimit is out of range"` (before API-04 rework `1.5` was silently stored as `1` and `"7"` as `7`). |
| `perUserUsageLimit` | **New**, optional integer `1..usageLimit`, same strict-integer rule as `usageLimit`. One customer can hold at most this many **live** redemptions (orders that are not cancelled, failed or expired); enforced by checkout, by `GET /api/v1/tax?couponCode=` and shown as invalid by the cart preview. |
| `isActive` | Required boolean. |
| `startDate`, `expirationDate` | Required `yyyy-MM-dd`; `expirationDate` must not be before `startDate` (both days are valid; a one-day coupon has start = expiry). |

Responses `CouponDetailResponse` now also carries `perUserUsageLimit`. `CouponListResponse` now also carries `discountValue`, `maximumDiscountAmount`, `minimumCartAmountForDiscount`, `perUserUsageLimit` and `startDate` (no more one GET per row). All changes are additive. Coupon decimals stay JSON numbers (currency GHS implied); the "GHS 45.00" string format used by product/order money is a documented follow-up, not changed here.

### Errors

| Status | Body | When |
|---|---|---|
| `400` | `{description:"Validation failed", data:{<field>:<message>}}` | any rule above (field-name keys; the cross-field rules use the keys `percentageWithinRange`, `percentageCapPositive`, `expirationNotBeforeStart`, `perUserLimitWithinTotal`) |
| `400` | `{description:<message>}` | update: code changed, or `usageLimit` below the live redemptions; a non-integer `usageLimit`/`perUserUsageLimit` (see above) |
| `404` | `{description:"Coupon not found."}` | unknown id (also another tenant's id) |
| `409` | `{description:"A coupon with this code already exists.", data:{code:"COUPON_CODE_EXISTS"}}` | create with a code that exists (case-insensitive, also under a race). **Replaces** the old generic `"A constraint violation occurred"` 409 for this case. |
| `409` | `{description:"This coupon has been redeemed and cannot be deleted. Deactivate it instead.", data:{code:"COUPON_REDEEMED"}}` | delete of a coupon with a **live** redemption: a checkout in flight, or an order that is not cancelled / failed / expired. Redemptions of cancelled, failed or expired orders do not count. |
| `503` | `{description:"This coupon is being updated right now. Please try again.", data:{code:"COUPON_BUSY"}}`, header `Retry-After: 1` (rework 3) | the coupon row stayed locked longer than the lock wait limit (5 s by default): update, deactivate, delete, **checkout** (`POST /cart/checkout?couponCode=`), order cancel (owner / admin) and the verify poll when they give a redemption back or take it again. Nothing was changed; retry. In practice the coupon row is only ever held for one short statement (see "Lock order"), so this needs a stuck or very slow database. Payment webhooks never answer `503`: they answer `200` and apply the event asynchronously (retried in-process, see docs/api/checkout.md). |
| `503` | `{description:"The request collided with another update. Please try again.", data:{code:"TRY_AGAIN"}}`, header `Retry-After: 1` | (rework 2) order cancel (owner / admin), admin order status, verify poll, and (API-05) checkout: a deadlock / serialization / optimistic-lock conflict with a webhook or another cancel on the same rows that was still there after 3 in-process attempts. Rolled back, nothing changed; retry. Any deadlock or serialization failure anywhere in the API is this `503`, never a `500`. |

### Behaviour

- **Codes are case-insensitive.** Stored trimmed and upper-cased; checkout, preview and `/tax` accept any case. `LEGACY10` cannot be created next to `legacy10` (`409 COUPON_CODE_EXISTS`, enforced by a case-insensitive unique index). `PUT` that sends back a coupon's own code in any case is not a code change.
- **Legacy codes (migration V12, API-04 rework).** Coupons created before API-04 could be stored lower/mixed case or blank, and could not be redeemed. V12 upper-cases every code. Collisions are resolved without failing the deploy and without deleting rows: among codes equal after trim + upper-case, the row that was already upper-case keeps the code (it is the one checkout always found), otherwise the lowest id keeps it; every other row of the group is renamed `<CODE>-DUP-<id>` and **deactivated**; a blank code becomes `LEGACY-BLANK-<id>` and is deactivated. Those rows could never be redeemed before either. Orders keep pointing at the same coupon rows. Admin UI: show such codes like any other; the admin can delete or keep them.
- **Usage counter = live redemptions.** `usageCount` counts checkouts in flight plus orders that are not cancelled, failed or expired. When an order is cancelled (by the customer, by the admin while unpaid, or by the expiry job) or its payment fails, its redemption is **given back exactly once**: `usageCount` goes down by one and the customer's per-user slot is free again. A checkout that fails (Paystack error, rejection) or that is an idempotent replay does not count either. If a late payment revives a cancelled / failed order, the order takes its redemption back (the counter can then exceed `usageLimit` by that order; the customer paid with the discount). A **paid** order cancelled by the admin keeps its redemption (the refund is manual). Orders cancelled or failed before V12 were released by the migration (counters lowered accordingly).
- **Auto-deactivation and re-activation.** The checkout that takes the last redemption switches the coupon off (`isActive=false`). If a redemption is later given back, the coupon is switched on again **only if the limit was the reason it was off**; a coupon an admin deactivated (`/deactivate` or `PUT isActive:false`) stays off. Rule: any admin write of `isActive` overrides the automatic state. Coupons auto-deactivated before V12 are not re-activated automatically (the reason was not recorded); switch them on with `PUT`.
- **Revoke a leaked coupon: `POST /{id}/deactivate`.** Effective immediately for every checkout that has not reserved the coupon yet; a checkout that reserved it just before (it is then creating the order / talking to Paystack) completes and its order stands. Idempotent. Deactivate and edits never wait for a checkout's Paystack call: no transaction holds the coupon row across that call. A checkout locks it only inside its one-statement reservation (and its give-back), committed before / after the checkout transaction; a cancel, payment failure, expiry or late-payment revival locks it only with its very last statement before commit (see "Lock order"). So an edit / deactivate waits at most for one such statement. The cart preview then reports `coupon.valid=false`, `reason=COUPON_INVALID`. `PUT` with `isActive:false` does the same.
- **Lock order (rework 2): order -> stock -> coupon, coupon row last.** Every path that changes the usage counter for an order (owner / admin cancel, expiry and straggler release, `charge.failed`, a late payment after a merchant cancel, a late-payment revival) locks the order row, writes the order / payment / stock / product rows, and only then runs the one coupon `UPDATE`, immediately before commit. Since API-05 a checkout locks its product rows (ascending id) only AFTER the Paystack call, for the short stock reservation before commit, so a payment failure or cancel touching the same product waits at most for that short step, and it does not hold the coupon row while it waits: other checkouts of the coupon and admin edits are not blocked (before rework 2 they got `503 COUPON_BUSY` after 5 s). Cancel and payment failure use the same order, so they no longer deadlock; a conflict that still happens (two of them re-stocking the same product at once) is retried in-process and, if it persists, is `503 TRY_AGAIN`, never a `500`. Admin delete locks the coupon's orders first, then the coupon.
- **Checks before reserving (rework 2).** Checkout first decides everything that does not depend on the usage counter, without reserving: address, empty cart, unavailable items, idempotent replays (keyed and same-cart), sale items, unknown code, **per-user limit** and **minimum amount**. A request refused or replayed there never touches the counter. Active / date window / usage limit are decided only by the atomic reservation. Small visible change: when several reasons apply at once, a per-user-limit or minimum-amount message can now come before "usage limit has been reached" (all are `400`).
- **Remaining transient window.** A checkout that passed those checks holds its reservation until it either creates an order or gives it back. While it is in flight it counts, so with the coupon at `usageLimit - 1` a second customer checking out at the same moment can be refused "usage limit has been reached" although the first one then fails (Paystack error, the last units of a product going to another customer, or a concurrent per-user race). The window is the duration of one checkout and the counter is exact afterwards. Since API-05, concurrent checkouts of the same product no longer fail with an optimistic-lock `500`, so that source (formerly the biggest) is gone; the losers of a real stock race get `400` "Insufficient stock for: ..." and give their redemption back immediately. The UI can treat "usage limit has been reached" as final; a retry a moment later may succeed.
- **Redeemed coupons** can be edited (the usage limit cannot go below the live redemptions) and deactivated. **Delete** works when there is no live redemption (`409 COUPON_REDEEMED` otherwise); cancelled / failed / expired orders that used the coupon lose their coupon reference on delete but keep their discount amounts. The code is free to be created again afterwards.
- Editing `discountType`/`discountValue` on a redeemed coupon changes future redemptions only.
- **`GET /api/v1/tax?subtotal=&couponCode=`** applies the same per-user limit as checkout and the preview (`400` "You have already used this coupon the maximum number of times").

## 9. Payment settings (API-04)

`ADMIN` only. Write-only secret semantics are unchanged: the secret key is never returned or logged; `secretConfigured` says whether one is stored.

| Route | Method | Body | Response |
|---|---|---|---|
| `/api/v1/payment-settings` | GET | — | `PaymentSettingsResponse` |
| `/api/v1/payment-settings` | PUT | `PaymentSettingsRequest` | `PaymentSettingsResponse` |
| `/api/v1/payment-settings/rotate-webhook-token` | POST | — | `PaymentSettingsResponse` (new `webhookUrl`) |

`PaymentSettingsRequest`: `secretKey` (optional on update, blank keeps the stored one), `publicKey` (required), `enabled`.
`PaymentSettingsResponse`: `secretConfigured`, `publicKey`, `enabled`, `webhookUrl` (unchanged shape).

**New format checks (400, field-keyed, the message never echoes the value):** `secretKey` must look like `sk_test_...` / `sk_live_...`, `publicKey` like `pk_test_...` / `pk_live_...` (letters, digits, `_`, 4-200 chars after the prefix); when both are sent they must be the same mode (key `keyModesConsistent`). The check is shape only; the keys are not verified against Paystack.

**Webhook-token rotation.** `POST /rotate-webhook-token` mints a new token; the old webhook URL answers `404` from that moment. The admin must paste the returned `webhookUrl` into the Paystack dashboard; events Paystack sends to the old URL in between are not processed. Show a confirm dialog before calling it. Rotation works even before any key is saved. Rotations (and the first-time token minting on `PUT`) are serialised on the tenant row, so two rotations at the same time each return the URL they committed and the last one is the live one; if the row stays locked longer than the wait limit the call is a `503` with `data.code = "PAYMENT_SETTINGS_BUSY"` and `Retry-After: 1` (nothing changed, retry).

**Logs.** The webhook URL contains the tenant's secret token in its path (`/api/v1/webhook/{token}`). The application never records that path as a site visit (API-04 rework); operators must make sure reverse-proxy / load-balancer access logs mask or drop the path segment after `/api/v1/webhook/` too.

**Shop-wide discount (4.4):** `GET /api/v1/admin/shop-discount/current` now returns `404` unless a discount is active **and** inside its `startAt`..`endAt` window now (before it returned the newest active row even if expired or scheduled). `label` is limited to 100 characters (`400` otherwise).
