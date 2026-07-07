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
- `404` (ResourceNotFound) when none is configured. Treat 404 as "no shop-wide discount" — show empty state, not an error toast.

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
| `/api/v1/admin/shop-discount/current` | GET | ADMIN | — | `ShopWideDiscountResponse` (404 if none) |
| `/api/v1/admin/shop-discount/{id}` | DELETE | ADMIN | — | `204` |

Enum values: `discountType` ∈ `{ "PERCENTAGE", "FLAT" }`.

Money strings: `"<CURRENCY> <AMOUNT>"`, e.g. `"GHS 49.99"`.

Timestamps (`startAt`, `endAt`, `discountEndsAt`): same serialization as existing API date fields (e.g. order `createdAt`) — send/parse in that same format.
