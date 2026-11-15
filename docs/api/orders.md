# Admin order handling API (ORD-01)

Contract for the merchant (ADMIN) side of orders, plus the order-shape changes the storefront also sees. The checkout and
payment side stays in [checkout.md](checkout.md). Every response is `{ "description": string|null, "data": ... }`; errors
carry a stable machine code in `data.code` (table at the end), never parse `description`.

All routes are tenant scoped (schema per tenant, resolved from the request origin) and live under `/api/v1/order`.
Anonymous or foreign-tenant callers get 401, a signed-in non-ADMIN gets 403 on the admin routes, and an order of another
tenant is a 404 `ORDER_NOT_FOUND` exactly like a non-existent one (see [authorization-matrix.md](authorization-matrix.md)).

## Order shape (`OrderResponse`, additions in bold)

```json order-response-admin
{
  "description": null,
  "data": {
    "orderId": 42,
    "orderNumber": "PB-20260101-7KQ3M8XW2D",
    "reference": "T123456789012",
    "subtotal": "GHS 200.00",
    "originalSubtotal": "GHS 200.00",
    "automaticDiscountAmount": "",
    "discountAmount": "",
    "totalAmount": "GHS 220.00",
    "paymentStatus": "COMPLETED",
    "deliveryStatus": "CANCELLED",
    "lineItems": [],
    "orderDate": "2026-01-01T12:00:00",
    "orderedAt": "2026-01-01T12:00:00Z",
    "deliveryDetail": null,
    "taxes": {
      "orderTaxes": [
        {
          "id": 1,
          "taxName": "VAT",
          "taxAmount": 20.00,
          "taxRate": 10.00,
          "taxCode": "VAT",
          "currency": "GHS",
          "taxAmountFormatted": "GHS 20.00",
          "taxRatePercent": 10.00
        }
      ],
      "totalTaxAmount": "GHS 20.00",
      "totalAmountAfterTax": "GHS 220.00"
    },
    "refundRequired": true,
    "cancellable": false,
    "version": 4,
    "cancelledAt": "2026-01-02T09:30:00Z",
    "cancelSource": "ADMIN",
    "account": { "id": 7, "fullName": "Ama Mensah", "email": "ama@example.com" }
  }
}
```

| Field | Notes |
|---|---|
| `orderedAt` (new) | The creation instant in **UTC with an explicit offset** (ISO-8601, `Z`). Use this. |
| `orderDate` | **Deprecated.** Zone-less server-local timestamp, kept unchanged for existing clients. |
| `taxes.orderTaxes[].taxAmount` | Unchanged number (major units). Its currency is now explicit in `currency` and in `taxAmountFormatted` (`"GHS 20.00"`). |
| `taxes.orderTaxes[].taxRate` | **Deprecated.** Same value as `taxRatePercent`. The unit is a **percentage** (`10.00` = 10%, not 0.10). |
| `taxes.orderTaxes[].taxRatePercent`, `taxCode`, `currency`, `taxAmountFormatted` (new) | Unambiguous replacements. The tax objects of `POST /cart/preview` carry the same fields. |
| `version` (new) | Optimistic-concurrency token, also the `ETag` response header (`"4"`) of the two admin PUT/POST routes. Every committed change of the order moves it. |
| `cancelledAt` (new) | UTC instant of the cancel, `null` while the order is not cancelled. |
| `cancelSource` (new) | `CUSTOMER`, `ADMIN` or `EXPIRY`; `null` while not cancelled and for orders cancelled before ORD-01. |
| `account` (new) | **ADMIN responses only** (detail `GET /order/{n}`, the PUT and the refund acknowledgement). Owner `id`, `fullName`, `email`. The key is absent for customers (their own order) and in list rows. |
| `refundRequired` | Now correct in the PUT response too (it was always `false` there). |
| `reference` | Now present in the PUT response too. |

`OrderListResponse` (`GET /order/all`, `GET /order/my-orders`) gains `orderedAt` and `version`; `orderDate` is deprecated the same way.

## GET /api/v1/order/all (ADMIN)

| Query | Meaning |
|---|---|
| `paymentStatus`, `orderStatus` | exact filters (`orderStatus` is the delivery status), as before |
| `from`, `to` | order time range. Each is an ISO-8601 date (`2026-11-01`, a UTC day) or a date-time with offset (`2026-11-01T10:00:00Z`; encode `+` as `%2B`). `from` is inclusive; `to` as a **date includes that whole day**, as a **date-time is exclusive**. Unparseable: 400 `INVALID_DATE_FILTER`; `from` not before `to`: 400 `INVALID_DATE_RANGE` |
| `q` | text search, case-insensitive *contains* over the **order number, the delivery recipient name, the delivery phone number and the account e-mail**. `%`, `_` and `\` match literally. Blank = no search; more than 100 characters or any control character: 400 `INVALID_SEARCH` (never truncated) |
| `sort` | `createdAt` (default, descending), `orderNumber`, `status`, `deliveryStatus`, `id`, as `?sort=createdAt,desc`. The unique `id` is always the final tie-break, so pages never reorder. Any other property: 400 `INVALID_SORT`. **Before ORD-01 the sort was silently ignored.** |
| `page`, `size` | `page` > 1,000,000: 400 `INVALID_PAGE`; `size` is capped at 100 (larger values are clamped, and the server may cap lower) |

The list is built with a constant number of queries (page + count + one payment lookup), independent of the page size.

## PUT /api/v1/order/update-order-status/{orderNumber} (ADMIN)

Body: `{ "orderStatus": "PACKING", "version": 3 }`; `version` is optional (see concurrency). Response: `OrderResponse` above (with
`account`), `ETag: "<version>"`.

### Delivery status graph

```
PENDING -> PACKING -> OUT_FOR_DELIVERY -> DELIVERED          (forward only; skipping ahead is allowed)
   \__________\______________\_________-> CANCELLED          (from any non-final state)
```

| From | May go to |
|---|---|
| `PENDING` | `PACKING`, `OUT_FOR_DELIVERY`, `DELIVERED`, `CANCELLED` |
| `PACKING` | `OUT_FOR_DELIVERY`, `DELIVERED`, `CANCELLED` |
| `OUT_FOR_DELIVERY` | `DELIVERED`, `CANCELLED` |
| `DELIVERED`, `CANCELLED` | nothing (final) |

Rules, evaluated in this order after the order row is locked:

1. **Same status as now = 200 no-op** (nothing is changed, no e-mail, no audit row), for every status including the final
   ones, and regardless of `version`. A retry of a request that already succeeded is therefore a 200. *(Before ORD-01 a
   repeat on a final status was a 400; that is the only loosening.)*
2. `version` (or `If-Match`) present and different from the order's current version: **409 `ORDER_VERSION_CONFLICT`**,
   `data.currentVersion`, `data.deliveryStatus`. Reload and retry.
3. The order is already `DELIVERED` / `CANCELLED` and another status is requested: 400 `ORDER_FINAL_STATE` (same HTTP status
   and the same `description` text as before; only `data.code` is new).
4. A backwards move or any move not in the table: 400 `ORDER_TRANSITION_NOT_ALLOWED` (`data.from`, `data.to`).
5. `DELIVERED` while `paymentStatus` is not `COMPLETED` (unpaid, pending, failed or cancelled payment): 400 `ORDER_NOT_PAID`.

### Optimistic concurrency

Every response carries `version`; send it back in the body (`"version": 3`) **or** as `If-Match: "3"` (also `W/"3"` or `3`).
Both given and different: 400 `VERSION_MISMATCH`; malformed `If-Match`: 400 `INVALID_IF_MATCH`. Omitting both keeps the old
unconditional behaviour (**deprecated**: it still cannot overwrite a final status or break the graph, but two admins can
both move PACKING to OUT_FOR_DELIVERY unnoticed; the frontend should always send the version). Status changes of the same
order are serialised by a row lock, so two simultaneous requests never both apply.

### Cancelling (`orderStatus: "CANCELLED"`)

A real cancel, atomic and exactly-once, whatever the payment state:

| Order state | Effect |
|---|---|
| unpaid (`paymentStatus` `PENDING`/`INITIATED`) | stock reservation released, open payment `CANCELLED`, `paymentStatus` -> `CANCELLED`, `deliveryStatus` -> `CANCELLED`. `refundRequired` stays false. |
| payment already `FAILED`/`CANCELLED` | `deliveryStatus` -> `CANCELLED`, any straggling reservation released. `refundRequired` false. |
| paid (`COMPLETED`) | `deliveryStatus` -> `CANCELLED`, **`refundRequired` -> true**. If the order had not left the shop (from `PENDING` or `PACKING`) the consumed stock is returned to the shelf (inventory layers, a `RETURN` movement, the location ledger `SALE_RETURN`, `soldCount` reduced). From `OUT_FOR_DELIVERY` the goods are with the courier and are **not** restocked automatically (adjust stock by hand on return). The payment itself stays `COMPLETED` until you refund it outside Shopfi. |

The customer gets the usual status e-mail once, after the transaction commits. Stock is never released or returned twice
(a repeat cancel is the no-op of rule 1; the inventory calls only touch `RESERVED` / `CONSUMED` rows).

**Late payment of a cancelled order.** A `charge.success` (webhook or verify poll) for an order the **merchant** cancelled
never revives the order: the payment is recorded `COMPLETED` with `refundRequired: true`, the order stays `CANCELLED`, and any
straggling reservation is released. Orders cancelled by the customer or by expiry keep the checkout.md behaviour (a late
payment re-reserves the stock when it can). `deliveryStatus = CANCELLED` now counts as closed in the settlement.

## POST /api/v1/order/{orderNumber}/refund-acknowledgement (ADMIN)

No body. Call it once the shop has refunded the customer by hand. Sets `refundRequired` to `false`, stores who/when
(`payments.refund_acknowledged_by` = the admin's e-mail, `..._at`) and appends one `REFUND_ACKNOWLEDGED` row to
`order_audit_events` (actor e-mail and time only). Response: the `OrderResponse` (`refundRequired: false`, new `version`).

* No refund pending (never required, or already acknowledged, including the loser of two simultaneous calls): **409
  `REFUND_NOT_REQUIRED`**, nothing written. The effect is idempotent: exactly one audit row per refund.
* Unknown / other tenant's order: 404 `ORDER_NOT_FOUND`.

## Audit trail

`order_audit_events` (tenant schema, V11) is append-only: one row per admin status change (`STATUS_CHANGED`), per cancel
(`CANCELLED`, with the `refund_required` outcome) and per refund acknowledgement (`REFUND_ACKNOWLEDGED`), each with the
actor's e-mail and a timestamp. There is no read endpoint yet.

## Order numbers

New numbers are `PB-yyyyMMdd-` plus **10** characters from a 30-symbol alphabet (`2-9`, `A-Z` without `I L O U`) drawn from a
`SecureRandom` (about 49 bits; the old suffix was 5 hex characters, 20 bits). Existing numbers keep working unchanged;
treat an order number as an opaque string of up to 64 characters. Numbers are claimed atomically (unique index plus
`ON CONFLICT DO NOTHING`, up to 5 attempts), so two concurrent checkouts cannot get the same one.

Order numbers are not a way to look orders up: `GET /order/{orderNumber}` needs a signed-in CUSTOMER (own orders only) or ADMIN,
the cancel route is owner-only, and there is no anonymous or per-reference public lookup. Unknown, other customers' and other
tenants' numbers return the same 404.

## Error codes

| HTTP | `data.code` | Meaning |
|---|---|---|
| 400 | `ORDER_FINAL_STATE` | order is DELIVERED/CANCELLED and another status was requested |
| 400 | `ORDER_TRANSITION_NOT_ALLOWED` | backwards or otherwise disallowed move (`from`, `to`) |
| 400 | `ORDER_NOT_PAID` | DELIVERED requested for an order whose payment is not COMPLETED |
| 400 | `VERSION_MISMATCH` / `INVALID_IF_MATCH` | body `version` and `If-Match` differ / malformed |
| 400 | `INVALID_DATE_FILTER` / `INVALID_DATE_RANGE` | `from`/`to` unparseable / not ordered |
| 400 | `INVALID_SEARCH` / `INVALID_PAGE` / `INVALID_SORT` | `q` too long or with control characters / page too large / unsupported sort |
| 404 | `ORDER_NOT_FOUND` | unknown, other customer's (customer routes) or other tenant's order |
| 409 | `ORDER_VERSION_CONFLICT` | stale `version` (`currentVersion`, `deliveryStatus`) |
| 409 | `REFUND_NOT_REQUIRED` | nothing to acknowledge |

## What the frontend must adopt

1. Send `version` (or `If-Match`) with every status change; handle 409 `ORDER_VERSION_CONFLICT` by reloading the order.
2. Offer only the moves of the graph; do not offer `DELIVERED` for an order that is not paid; handle the new 400 codes.
3. Show `refundRequired` after a cancel and call the refund acknowledgement once refunded (409 `REFUND_NOT_REQUIRED` means someone already did).
4. Read `orderedAt`, `taxes.orderTaxes[].taxRatePercent`/`currency`/`taxAmountFormatted` instead of `orderDate`/`taxRate`; show `account` on the admin detail.
5. Wire `q`, `from`, `to` and `sort` of `/order/all`; a repeat of a final status is now a 200.
