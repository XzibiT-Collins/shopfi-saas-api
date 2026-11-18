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
    "authorizationUrl": null,
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
| `authorizationUrl` (new, PAY-03 rework) | The Paystack checkout URL, **only** on the owner's own detail `GET /order/{n}` (and cancel) as CUSTOMER while the order is open (payment `INITIATED`, now before `expiresAt`). `null` once paid, failed, cancelled or expired, and **always `null` in ADMIN responses** (detail, PUT, refund acknowledgement, return received). Not in list rows. Other customers / tenants: 404 as before. See [checkout.md](checkout.md) "Continue to payment". |
| `reference` | Now present in the PUT response too. |

`OrderListResponse` (`GET /order/all`, `GET /order/my-orders`) gains `orderedAt` and `version`; `orderDate` is deprecated the same way.

## GET /api/v1/order/all (ADMIN)

| Query | Meaning |
|---|---|
| `paymentStatus`, `orderStatus` | exact filters (`orderStatus` is the delivery status), as before |
| `from`, `to` | order time range. Each is an ISO-8601 date (`2026-11-01`, a UTC day) or a date-time with offset (`2026-11-01T10:00:00Z`; encode `+` as `%2B`). `from` is inclusive; `to` as a **date includes that whole day**, as a **date-time is exclusive**. Unparseable: 400 `INVALID_DATE_FILTER`; `from` not before `to`: 400 `INVALID_DATE_RANGE` |
| `q` | text search, case-insensitive *contains* over the **order number, the delivery recipient name, the delivery phone number and the account e-mail**. `%`, `_` and `\` match literally. Blank = no search; more than 100 characters or any control character: 400 `INVALID_SEARCH` (never truncated) |
| `sort` | `createdAt` (default, descending), `orderNumber`, `status`, `deliveryStatus`, `id`, as `?sort=createdAt,desc`. The unique `id` is always the final tie-break, so pages never reorder. Any other property: 400 `INVALID_SORT`. **Before ORD-01 the sort was silently ignored.** |
| `page`, `size` | `size` is capped at **50** (larger values are clamped; ORD-02, was 100: the prod profile already clamped to 50). A page may start at most at row 10,000: `page * size` (after the clamp) >= 10,000, or `page` > 1,000,000: 400 `INVALID_PAGE` (`data.maxOffset`, `data.size` on the offset error). Deep offsets scan and discard every earlier row; narrow the list with `from`/`to`, `q` or a status filter instead. |

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
2. `version` (or `If-Match`) present and different from the order's current version (**older or newer**; a version the server never issued is a conflict too): **409 `ORDER_VERSION_CONFLICT`**,
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
| paid (`COMPLETED`) | `deliveryStatus` -> `CANCELLED`, **`refundRequired` -> true**, the sale is reversed in the books and dropped from the sales reports (see *Paid cancel: books and reports*), and **the coupon redemption is given back** (ORD-02b, see below). If the order had not left the shop (from `PENDING` or `PACKING`) the consumed stock is returned to the shelf (inventory layers, a `RETURN` movement, the location ledger `SALE_RETURN` **at the location the units were deducted from**, `soldCount` reduced). From `OUT_FOR_DELIVERY` see *Restock policy* below. The payment itself stays `COMPLETED` until you refund it outside Shopfi. |

The customer gets the usual status e-mail once, after the transaction commits. Stock is never released or returned twice
(a repeat cancel is the no-op of rule 1; the inventory calls only touch `RESERVED` / `CONSUMED` rows).

**Coupon of a cancelled order (ORD-02b, PO decision 2026-11-18).** Every admin cancel, paid or unpaid, gives the order's
coupon redemption back, like the owner cancel, expiry and `charge.failed` always did: the coupon's `usageCount` goes down
by one (a coupon that was switched off only because it hit its usage limit is switched on again) and the order no longer
counts towards the customer's `perUserUsageLimit`, so the customer can use the coupon again. Exactly once per order
(`orders.coupon_redemption_released`, under the order row lock), also for repeated or simultaneous cancels. *(Before
ORD-02b a paid cancel kept the redemption.)* The books need nothing extra: the `SALE_REVERSAL` already takes the coupon
discount back. Paid orders cancelled before this release were released by tenant migration V16.

### Returns of `DELIVERED` orders (not supported yet: ORD-07)

A delivered order is final. What the routes answer for a `DELIVERED` order today (nothing is changed by any of them):

| Call | Answer |
|---|---|
| `PUT .../update-order-status/{n}` with `CANCELLED` (or any other status) | 400 `ORDER_FINAL_STATE` ("Order is already delivered and can not be updated.") |
| `POST .../{n}/return-received` | 409 `RETURN_NOT_APPLICABLE` (`data.deliveryStatus: "DELIVERED"`, `data.paymentStatus: "COMPLETED"`) |
| `POST .../{n}/refund-acknowledgement` | 409 `REFUND_NOT_REQUIRED` |
| customer `POST /order/{n}/cancel` | 409 `ORDER_NOT_CANCELLABLE` (paid) |

The sale stays booked and the coupon redemption stays used. A merchant who takes goods back after delivery records the
refund and stock by hand (manual journal entry, stock receive) until ORD-07 adds a return flow.

### Paid cancel: books and reports (ORD-02)

* **Journal.** One `SALE_REVERSAL` entry (reference type `ORDER`, the order number), dated at the cancel, mirrors the
  order's `SALE` entry line by line: revenue, tax and discounts are taken back, and the cash line becomes a credit to
  **Refunds Payable** (2100): the money is still in the shop until you refund it. COGS / inventory are reversed only
  when the goods are back on the shelf (at the cancel from `PENDING`/`PACKING`, or later via `return-received`). Closed
  periods are never rewritten. Each part is posted at most once per order (a repeated or concurrent cancel never
  doubles it). An order that was never booked as a sale (e.g. a payment that arrived after a merchant cancel, booked
  as a `LATE_PAYMENT` since ORD-02b, see below) has nothing to reverse.
* **Refund acknowledgement** posts a `REFUND` entry: debit Refunds Payable, credit Cash and Bank, for what the order
  still owes (once): the Refunds Payable credits of its `SALE_REVERSAL` and `LATE_PAYMENT` entries minus earlier `REFUND`s. Cash and Bank may not go negative; if the books show less cash than the refund the acknowledgement
  is refused with **409 `INSUFFICIENT_CASH`** and nothing is written (see the refund acknowledgement below).
* **Ledger balances are concurrency-safe** (ORD-02 rework). Every posting (sale, paid cancel, refund, walk-in sale,
  manual entry, ...) moves each account with one atomic `UPDATE ... SET balance = balance + delta`, in ascending account
  id order, as the last lock of its transaction. Simultaneous cancels, acknowledgements and payment webhooks therefore
  never lose a balance update, and the negative-balance guard sees the true balance.
* **The negative-balance guard checks the net per account** (ORD-02 rework). The lines of one entry are first netted per
  account (debits minus credits, or the reverse for liability / equity / revenue accounts) and the account moves once by
  that net; the guard looks at the balance after the net move. A **manual entry** is refused (400) when any account it
  touches would end below zero; an automatic posting is refused only for Cash and Bank, Mobile Money and Inventory (the
  refund acknowledgement turns that into 409 `INSUFFICIENT_CASH`), other accounts only log an alert. Consequence for
  manual entries: a debit and a credit to the same account in one entry offset each other, so e.g. crediting Cash 500 and
  debiting Cash 400 in one entry needs only 100 in Cash, not 500; lines are no longer checked one by one.
* **Ledger balances are only moved by that UPDATE** (ORD-02b). `ledger_accounts.balance` is written by the entity only
  when an account is created (its opening balance); any other save of a `LedgerAccount` leaves the stored balance
  alone, so an account object loaded before a posting can never write a stale balance back.
* **Sales analytics** (`GET /admin/metrics/sales-analytics`): revenue and COGS are net (credits minus debits / debits
  minus credits) and `ONLINE`/`ALL` include `SALE_REVERSAL`, so the cancel takes the sale back out **in the bucket of the
  cancel date**. Operating expenses are net too (a discount credited back no longer counts; this also nets manual
  credits to an expense account). "Total Orders" counts completed orders that are not cancelled.
* **Analytics vs order reports: different days.** The analytics are journal-based and dated by the transaction: the
  sale counts on the payment date and its reversal on the **cancel date**. A cancel on a later day than the sale
  therefore shows the sale on its day and a **negative** revenue (and COGS, if the goods came back) on the cancel day;
  a bucket can be negative overall. The order-based reports below are keyed by the **order date** and simply drop the
  cancelled order from it, so for the sale's day they show less than the analytics until the cancel day is included.
* **Order-based reports** exclude paid orders that were cancelled (`deliveryStatus = CANCELLED`), keyed by the order
  date as before: dashboard total and daily revenue, top products, top customers, coupon metrics (per coupon and the
  list), the completed-order count, the customer list's and customer details' `totalSpent` (paid, not cancelled
  orders; `orderCount` still counts every order), and the items-sold report (`/admin/inventory/sales/items-sold`, its
  per-product view and totals). An order whose `deliveryStatus` is empty (`NULL`, legacy rows) is not cancelled and is
  counted.

### Restock policy for a paid cancel from `OUT_FOR_DELIVERY` (ORD-02 decision)

The goods are with the courier, so they are **not** put back automatically (they would become sellable online while
nobody has them). They stay `CONSUMED`; the revenue is reversed at the cancel, the cost (COGS) stays booked. When the
parcel is back, call `POST /api/v1/order/{orderNumber}/return-received` (below): it restocks exactly those units and
reverses the cost. A parcel that never comes back stays written off; nothing else needs doing.

### Location of a return (ORD-02)

Each consumed allocation stores the location its units were deducted from (`inventory_allocations.fulfilment_location_id`,
tenant migration V14), so a return goes back there even if the e-commerce fulfilment source was changed in between.
V14 backfilled existing rows from the ledger (`stock_transfers` `SALE_DEDUCTION` of the same order and product, newest
first). Legacy rows it could not match (deducted before V6 recorded references) fall back to the current fulfilment
source, the pre-ORD-02 behaviour. Units that were never deducted (no fulfilment source configured at the sale) are not
added to any location.

**Late payment of a cancelled order.** (It also moves the order's `version`, so an admin holding the old version gets a 409 and reloads to see `refundRequired`.) A `charge.success` (webhook or verify poll) for an order the **merchant** cancelled
never revives the order: the payment is recorded `COMPLETED` with `refundRequired: true`, the order stays `CANCELLED`, and any
straggling reservation is released. The coupon redemption stays given back.

**Late payment on a closed order is booked (ORD-02b, PO decision 2026-11-18).** Whenever a late `charge.success` leaves an
order closed with `refundRequired: true` (a merchant-cancelled order, or a customer-cancelled / expired / failed order whose
stock is gone), the money the shop received is booked once, dated at the payment, as a `LATE_PAYMENT` journal entry
(reference type `ORDER`, the order number):

| Account | Debit | Credit |
|---|---|---|
| 1000 Cash and Bank | payment amount | |
| 2100 Refunds Payable | | payment amount |

No revenue, tax, discount or COGS: there was no sale, so the analytics and order reports do not change. The refund
acknowledgement then posts the usual `REFUND` entry (debit 2100 Refunds Payable, credit 1000 Cash and Bank, same amount),
which clears both, with the same 409 `INSUFFICIENT_CASH` rule. Exactly once per order: duplicate or redelivered webhooks,
verify polls and retried settlement attempts find the payment already `COMPLETED` (re-read under the order row lock)
and post nothing, and the posting itself refuses a second `LATE_PAYMENT` for the order. Late payments settled before
ORD-02b were not booked; their acknowledgement posts nothing (as before), the books stay balanced. Orders cancelled by the customer or by expiry keep the checkout.md behaviour (a late
payment re-reserves the stock when it can; the revived order is live again, so `cancelledAt` and `cancelSource` go back to `null`). `deliveryStatus = CANCELLED` now counts as closed in the settlement.

## POST /api/v1/order/{orderNumber}/refund-acknowledgement (ADMIN)

No body. Call it once the shop has refunded the customer by hand. Sets `refundRequired` to `false`, stores who/when
(`payments.refund_acknowledged_by` = the admin's e-mail, `..._at`) and appends one `REFUND_ACKNOWLEDGED` row to
`order_audit_events` (actor e-mail and time only). Response: the `OrderResponse` (`refundRequired: false`, new `version`).

* No refund pending (never required, or already acknowledged, including the loser of two simultaneous calls): **409
  `REFUND_NOT_REQUIRED`**, nothing written. The effect is idempotent: exactly one audit row per refund.
* The books show less in Cash and Bank than the refund (the `REFUND` entry would take it below zero): **409
  `INSUFFICIENT_CASH`**, nothing written (`refundRequired` stays `true`, no audit row, no journal entry). `data`:
  `orderNumber`, `currentBalance` (Cash and Bank before the refund) and `attemptedAmount` (the refund), both decimal
  strings, e.g. `{"code": "INSUFFICIENT_CASH", "orderNumber": "PB-...", "currentBalance": "19.00", "attemptedAmount": "20.00"}`.
  Record the missing cash (e.g. a manual journal entry) and retry.
* Unknown / other tenant's order: 404 `ORDER_NOT_FOUND`.

## POST /api/v1/order/{orderNumber}/return-received (ADMIN, ORD-02)

No body. The goods of a **paid order that was cancelled while `OUT_FOR_DELIVERY`** are back in the shop. Restocks the
consumed units (inventory layers, `RETURN` movement, location ledger `SALE_RETURN` at the location they were deducted
from, `soldCount`), reverses the COGS in the books, appends a `RETURN_RECEIVED` audit row and moves the `version`.
Response: the `OrderResponse` with `ETag`. Exactly once (row lock):

* Nothing consumed left (already restocked, including the loser of two simultaneous calls, or an order cancelled from
  `PENDING`/`PACKING`, which was restocked at the cancel): **409 `NOTHING_TO_RESTOCK`**.
* The order is not `CANCELLED` or was never paid: **409 `RETURN_NOT_APPLICABLE`** (`data.deliveryStatus`, `data.paymentStatus`).
* Unknown / other tenant's order: 404 `ORDER_NOT_FOUND`.

## Audit trail

`order_audit_events` (tenant schema, V11) is append-only: one row per admin status change (`STATUS_CHANGED`), per cancel
(`CANCELLED`), per return received (`RETURN_RECEIVED`) and per refund acknowledgement (`REFUND_ACKNOWLEDGED`), each with
the actor's e-mail and a timestamp. `refund_required` on a `CANCELLED` row is the order's refund state right after the
cancel: true for a paid cancel, and also for a failed/cancelled-payment order whose late payment still owes a refund
(ORD-02, X20); always false on `STATUS_CHANGED` rows.

Since V14 the database enforces it: UPDATE and DELETE of audit rows are rejected (`order_audit_events is append-only`),
and the order foreign key is `ON DELETE RESTRICT` (was `CASCADE`): an order with history cannot be deleted (Shopfi never
deletes orders). A whole-table `TRUNCATE` (operator / test reset, `TRUNCATE orders ... CASCADE`) and dropping the tenant
schema still work.

### GET /api/v1/order/{orderNumber}/audit-events (ADMIN, ORD-02)

The order's rows, **oldest first** (by id; a `sort` parameter is ignored), paged: `page` (0-based), `size` (default 20,
capped at 50, as everywhere under the prod pageable limit; `page` > 1,000,000: 400 `INVALID_PAGE`). Unknown / other tenant's order: 404 `ORDER_NOT_FOUND`.

```json
{
  "description": null,
  "data": {
    "content": [
      { "id": 12, "eventType": "CANCELLED", "fromStatus": "OUT_FOR_DELIVERY", "toStatus": "CANCELLED",
        "refundRequired": true, "actorEmail": "owner@shop.test", "createdAt": "2026-11-17T09:30:00Z" }
    ],
    "totalPages": 1, "totalElements": 1, "size": 20, "number": 0, "numberOfElements": 1,
    "isFirst": true, "isLast": true, "isEmpty": false
  }
}
```

`eventType`: `STATUS_CHANGED`, `CANCELLED`, `RETURN_RECEIVED`, `REFUND_ACKNOWLEDGED`. `fromStatus`/`toStatus` are null on
`REFUND_ACKNOWLEDGED`. `createdAt` is a UTC instant.

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
| 409 | `RETURN_NOT_APPLICABLE` | return-received on an order that is not a cancelled paid order |
| 409 | `NOTHING_TO_RESTOCK` | return-received when no consumed units are left |
| 409 | `INSUFFICIENT_CASH` | refund acknowledgement when Cash and Bank is lower than the refund (`orderNumber`, `currentBalance`, `attemptedAmount`) |

## What the frontend must adopt

1. Send `version` (or `If-Match`) with every status change; handle 409 `ORDER_VERSION_CONFLICT` by reloading the order.
2. Offer only the moves of the graph; do not offer `DELIVERED` for an order that is not paid; handle the new 400 codes.
3. Show `refundRequired` after a cancel and call the refund acknowledgement once refunded (409 `REFUND_NOT_REQUIRED` means someone already did). On 409
   `INSUFFICIENT_CASH` tell the admin the books show too little cash (`data.currentBalance` vs `data.attemptedAmount`).
4. Read `orderedAt`, `taxes.orderTaxes[].taxRatePercent`/`currency`/`taxAmountFormatted` instead of `orderDate`/`taxRate`; show `account` on the admin detail.
5. Wire `q`, `from`, `to` and `sort` of `/order/all`; a repeat of a final status is now a 200.
6. (ORD-02) Request at most 50 rows per `/order/all` page and do not page past row 10,000 (400 `INVALID_PAGE`); offer the
   date range / search instead of deep paging.
7. (ORD-02) For an order cancelled while out for delivery, offer "Return received" (`POST .../return-received`); show the
   audit trail from `GET .../audit-events`.
