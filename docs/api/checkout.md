# Checkout, payment and order API (PAY-01)

Stable contract for the storefront checkout. All paths are on the API host; the tenant is resolved from the request
origin exactly as for every other storefront call. Every response is `{ "description": string|null, "data": ... }`.
Errors carry a stable machine code in `data.code` (table at the end); never parse `description`.

## Tenant scoping and ownership

* Every endpoint here runs inside the tenant resolved by the tenant filter (schema per tenant). Orders, payments,
  references, idempotency keys and delivery details of another tenant do not exist as far as the caller can tell.
* Order, payment and delivery-detail access is owner scoped (the logged-in CUSTOMER). Someone else's order, another
  tenant's order and a non-existent one return the SAME 404 body (no enumeration).
* The client never sends amounts or statuses. Totals come from the server; a payment only becomes paid through the
  signature-verified webhook or the server-side provider check (never from client input).

## POST /api/v1/cart/checkout

Role CUSTOMER. Creates the order from the caller's cart, reserves stock and initialises the Paystack payment.

| Input | Where | Notes |
|---|---|---|
| `couponCode` | query, optional | blank = none |
| `deliveryDetailId` | query, optional | one of the caller's own addresses; default address when absent; not owned/unknown = 404 `DELIVERY_DETAIL_NOT_FOUND` |
| `Idempotency-Key` | header, optional | 8-64 chars of `A-Za-z0-9._:-`, scoped per tenant and customer; else 400 `IDEMPOTENCY_KEY_INVALID` |

Response 200 (`status`/`message`/`data` keep the Paystack shape; the order fields are new):

```json checkout-response
{
  "description": null,
  "data": {
    "status": true,
    "message": "Authorization URL created",
    "data": {
      "authorization_url": "https://checkout.paystack.com/abc123def",
      "access_code": "abc123def",
      "reference": "T123456789012"
    },
    "orderId": 42,
    "orderNumber": "ORD-20260101-0001",
    "paymentStatus": "INITIATED",
    "totalAmount": "GHS 220.00",
    "expiresAt": "2026-01-01T13:00:00Z",
    "idempotentReplay": false
  }
}
```

`expiresAt` is an ISO-8601 instant in UTC with an explicit offset (`Z`), safe to parse with `Date`/`Instant`; it is order creation plus the reservation TTL.

The storefront redirects to `data.data.authorization_url` (validate it against the Paystack origin) and remembers
nothing else: the order number and reference come back in this response.

### Idempotency semantics

* Same key + same request (cart lines, coupon, address) while the order is still open: no second order, no second
  Paystack call; the original response is returned with `idempotentReplay: true`.
* Same key, different request: 422 `IDEMPOTENCY_KEY_REUSED`.
* Same key, order no longer payable (paid, failed, cancelled, expired): 409 `IDEMPOTENT_ORDER_CLOSED` with
  `data.orderNumber` and `data.paymentStatus`; use a new key to start over.
* Concurrent requests of one customer are serialised server side; failed initialisations leave nothing behind, so the
  same key can be retried.

### One open order per customer (PAY-03)

A customer has at most ONE open order per tenant. "Open" = payment `INITIATED` and created less than the reservation
TTL ago (`expiresAt` in the future; default 1 h). Paid (`COMPLETED`), `FAILED`, `CANCELLED` and expired orders are not
open (an expired order the cleanup job has not cancelled yet does not count either). This holds whatever
`Idempotency-Key` each tab / device sends, and without a key. The rules apply when the key is new (or absent); a key
that already created an order keeps the semantics above (replay / `422` / `409 IDEMPOTENT_ORDER_CLOSED`).

* **Same cart** = the same request fingerprint: the cart lines (product id + quantity, order of lines ignored), the
  coupon code (trimmed, case-insensitive, none = blank) and the delivery address id (the explicit `deliveryDetailId`, or
  the default address's id when absent). Prices, taxes and discounts are NOT part of it: the open order keeps the
  totals it was created with until `expiresAt` (it is the quote the customer is paying), exactly like a keyed replay.
* Same cart, open order exists: that order is returned: `200`, `idempotentReplay: true`, the SAME `orderNumber`,
  `orderId`, `totalAmount`, `expiresAt` and Paystack `authorization_url` / `access_code` / `reference`. No second
  order, no second Paystack call, no second stock or coupon reservation. The authorization URL is not refreshed: it
  stays valid for the life of the open order, and a new Paystack transaction for the same order would give one order
  two payable references.
* Different cart (any line, quantity, coupon or address differs), open order exists: `409` `OPEN_ORDER_EXISTS` with
  `data.orderNumber` and `data.expiresAt` of the open order. Nothing is created, reserved or cancelled and the key is
  not used up. The storefront offers "continue to payment" (see "Continue to payment" below) or "cancel it"
  (`POST /api/v1/order/{orderNumber}/cancel`) and then re-sends the checkout (the same key may be re-sent). The old
  order is never cancelled automatically: the other tab may already be on the Paystack page, and cancelling an order
  that is then paid leaves a paid-but-cancelled order (stock re-reserved or a refund).
* Same cart, but the open order cannot be resumed (its payment has no stored Paystack URL: a legacy or corrupt row):
  also `409` `OPEN_ORDER_EXISTS` naming that order, with the description "You already have an unpaid order for this
  cart that cannot be resumed; cancel it first." (it does NOT claim a different cart). No new Paystack transaction is
  started for it (one order, one reference). Its order detail has `authorizationUrl: null`, so the only way on is
  cancel, then re-send the checkout. Never parse `description`: the code and `data` are the same in both 409 cases.
* A key that was answered with the open order of another key is not bound to that order: re-sending it returns the
  open order while it is open; once that order is closed the key behaves like a new key.
* Concurrency: checkouts of one customer take the customer row lock first, so of two tabs racing with different keys
  the second one waits and then sees the first one's committed order: same cart -> that order, different cart ->
  `409 OPEN_ORDER_EXISTS`. A coupon redemption the waiting request reserved is given back.

#### Continue to payment (PAY-03 rework)

The owner's order detail `GET /api/v1/order/{orderNumber}` (role CUSTOMER, own orders only) carries
`authorizationUrl`: the Paystack checkout URL of that order's payment, the same value checkout returned as
`data.data.authorization_url`. It is set ONLY while the order is open (payment `INITIATED` and now before
`expiresAt`) and is `null` for paid (`COMPLETED`), `FAILED`, `CANCELLED` and expired orders (also an expired order the
cleanup job has not cancelled yet), and when the payment has no stored URL. It is never in list rows
(`my-orders`, `/all`), always `null` in ADMIN responses (the merchant is not the payer), and another customer's or
another tenant's order stays the usual 404 `ORDER_NOT_FOUND`. `reference` (already present) is the Paystack
reference. The Paystack `access_code` is not exposed: the storefront uses the redirect flow (`authorization_url`), not
the inline popup that needs the access code; it can be added if the inline flow is adopted.

So "continue to payment" after a `409 OPEN_ORDER_EXISTS` is: `GET /api/v1/order/{data.orderNumber}` and, when
`authorizationUrl` is non-null and `Date.now() < Date.parse(expiresAt)`, redirect to it (validate it against the
Paystack origin, as after checkout). Re-sending the SAME cart as that order also returns the URL (`200`,
`idempotentReplay: true`). When `authorizationUrl` is null, offer only "cancel it".

#### Frontend notes (UI-04d)

* **Treat the open-order marker as dead once `now >= expiresAt`**, even if the order still reads `INITIATED` (the
  cleanup job cancels it up to `cleanup-interval-ms`, default 15 min, later). The server already treats it as not open:
  it no longer blocks a checkout and its detail no longer carries `authorizationUrl`. Do not offer "continue to payment"
  for it. **Pre-existing risk (not new in PAY-03):** the old Paystack URL itself is not revoked at `expiresAt`; a tab
  that already holds it can still pay until the expiry job cancels the order, and even after that a late payment is
  accepted (stock re-reserved, or `refundRequired` when the stock is gone; see "Webhook behaviour"). Never re-open an
  old URL after `expiresAt`.
* **Changing the default delivery address counts as a different cart.** A checkout without `deliveryDetailId` uses the
  current default address's id, so after the customer switches the default, the same lines and coupon are a `409
  OPEN_ORDER_EXISTS` against the order created for the old address. Offer pay or cancel as for any other change.
* **A product in the cart deactivated or delisted since the order was created:** checkout answers `400` with
  `data.code` `PRODUCT_UNAVAILABLE` (description unchanged, "One or more products in your cart are no longer available
  for ecommerce purchase.") before any open-order check. If the customer has an open order (from the marker), offer
  "cancel it" (`POST /api/v1/order/{orderNumber}/cancel`) and ask them to remove the product from the cart. The open
  order itself can still be paid via its `authorizationUrl` while it is open.

`callback_url` is sent to Paystack automatically: the tenant storefront origin (verified custom domain, else
`<slug>.<base-domain>`) + `/checkout/callback`; Paystack redirects the browser to
`/checkout/callback?reference=<reference>&trxref=<reference>`. If no origin can be derived (local dev) it is omitted.

## POST /api/v1/cart/preview

Role CUSTOMER. Server computed totals (what checkout would charge now) and coupon validation. Creates nothing and does
not consume the coupon. Rate limited per customer (429 with `Retry-After`) when a coupon is supplied.

```json cart-preview-request
{ "couponCode": "SAVE10", "deliveryDetailId": 7 }
```

Both fields are optional (the body may be omitted). No delivery fee exists: `deliveryFee` is always `GHS 0.00`.

```json cart-preview-response
{
  "description": null,
  "data": {
    "subtotal": "GHS 200.00",
    "originalSubtotal": "GHS 200.00",
    "automaticDiscountAmount": "GHS 0.00",
    "couponDiscountAmount": "GHS 20.00",
    "discountedSubtotal": "GHS 180.00",
    "taxes": {
      "orderTaxes": [ { "id": null, "taxName": "VAT", "taxAmount": 22.50, "taxRate": 12.5 } ],
      "totalTaxAmount": "GHS 22.50",
      "totalAmountAfterTax": "GHS 202.50"
    },
    "deliveryFee": "GHS 0.00",
    "totalAmount": "GHS 202.50",
    "coupon": { "code": "SAVE10", "valid": true, "reason": null, "message": null }
  }
}
```

A coupon that cannot be applied does not fail the call: totals are returned without it and `coupon.valid=false` with
`reason` one of `COUPON_INVALID` (unknown, inactive, not started, expired or used up: deliberately not distinguished),
`COUPON_MIN_AMOUNT`, `COUPON_NOT_COMBINABLE` (cart has discounted items), `COUPON_PER_USER_LIMIT` (API-06: this
customer has already used the coupon `perUserUsageLimit` times; `message` is the same text checkout returns, "You have
already used this coupon the maximum number of times"). The per-user limit is only checked for a coupon that is
otherwise valid, so it reveals nothing to a customer who has not redeemed the coupon before. Empty cart: 400.
`GET /api/v1/cart` items now carry `lineTotal` (unit price x quantity, server computed); never multiply on the client.

## GET /api/v1/payment/verify?reference=

Role CUSTOMER. `reference` is the Paystack reference (`^[A-Za-z0-9._=-]{6,100}$`, else 400 `INVALID_REFERENCE`).
Only the caller's own payments in the current tenant are visible; anything else is 404 `PAYMENT_NOT_FOUND`.

```json payment-verify-response
{
  "description": null,
  "data": {
    "orderId": 42,
    "orderNumber": "ORD-20260101-0001",
    "reference": "T123456789012",
    "paymentStatus": "COMPLETED",
    "settled": true,
    "refundRequired": false
  }
}
```

`paymentStatus` is the order's payment status: `INITIATED` (or `PENDING`) means "not settled yet, poll again",
`COMPLETED`, `FAILED`, `CANCELLED` are final (`settled: true`). While unsettled the server asks Paystack itself (the
tenant's own secret key, at most once per 5 s per payment) and applies the answer exactly like the webhook; if Paystack
is unreachable the stored state is returned. `refundRequired: true` means the customer paid for an order that had
already been cancelled/expired and whose stock was gone: the order stays cancelled and the shop must refund.
Calling it repeatedly is safe (idempotent, read-mostly).

## POST /api/v1/order/{orderNumber}/cancel

Role CUSTOMER, own orders only. Cancels an unpaid order (PENDING/INITIATED), releases its stock reservation exactly
once and returns the order (`paymentStatus: CANCELLED`). Already cancelled: 200 unchanged (idempotent). Paid or failed:
409 `ORDER_NOT_CANCELLABLE`. Someone else's/unknown: 404 `ORDER_NOT_FOUND`.

Abandoned orders are also cancelled automatically: reservations older than `inventory.reservation.expiry-hours`
(default 1 hour, env `INVENTORY_RESERVATION_EXPIRY_HOURS`) are released by a job that runs every
`inventory.reservation.cleanup-interval-ms` (default 15 min) once per active tenant. `expiresAt` in the checkout
response is order creation + that TTL (cancellation happens at or shortly after it). A payment that still succeeds
after cancellation is handled (see webhook).

## Orders

`GET /api/v1/order/my-orders` (page), `GET /api/v1/order/all` (admin) and `GET /api/v1/order/{orderNumber}` now include `reference` (the Paystack
reference, null until initialised) so an order is found by the callback reference; `OrderResponse` also has
`cancellable` (false once the payment is COMPLETED, FAILED or CANCELLED). Both also carry `refundRequired`: true means the
customer paid but the order had already been cancelled/expired and could not be re-reserved, so the **merchant must
refund**; the admin list `GET /api/v1/order/all` and the detail show it for that purpose. `GET /api/v1/order/{orderNumber}`
returns 404 with `data.code` `ORDER_NOT_FOUND` for unknown, other customers' and other tenants' orders (identical body).
`OrderResponse` (detail and cancel) also has `authorizationUrl` (PAY-03 rework): the owner's Paystack URL while the
order is open, else `null` (see "Continue to payment"); list rows do not have it.
`orderDate` is a zone-less server-local timestamp (legacy field); use `expiresAt` for anything time-critical.
`my-orders` and `/all` sorts only by `createdAt`, `orderNumber`, `status`, `deliveryStatus`, `id`
(`?sort=createdAt,desc`); any other sort property is 400 `INVALID_SORT`.

## Webhook behaviour (`POST /api/v1/webhook/{token}`, unchanged routing)

The per-tenant token selects the tenant, the `X-Paystack-Signature` HMAC-SHA512 is verified with that tenant's secret
(constant-time compare). Then, per event type:

* `charge.success`: completes the payment. Amount and currency are required and must match the order; an event without them is ignored (fail closed) and the customer's verify poll settles it from the provider's own data. Idempotent.
  A late success for an order that is FAILED or CANCELLED/expired re-reserves the stock and completes it; if the stock
  is gone the payment is recorded COMPLETED with `refundRequired` and the order stays closed; the cash is booked once as a `LATE_PAYMENT` journal entry (Cash and Bank / Refunds Payable, no sale, ORD-02b, see orders.md). Any other failure while re-reserving (database/pool timeout) is not treated as out of stock: nothing is changed and the next webhook or verify poll retries.
* `charge.failed`: the only event that marks the payment/order FAILED and releases the stock. Never overrides COMPLETED.
* any other event type: ignored.

EVT-01c: the event is applied **before** the endpoint answers, and `200` means it committed (or can never be applied:
unparseable body, no reference, an ignored event type, an unknown reference, an amount mismatch). A transient database
failure while applying it (deadlock victim, serialization failure, optimistic-lock conflict on a shared product row,
lock timeout / `COUPON_BUSY`) is first retried in-process up to 3 times, each attempt a fresh transaction (API-04
rework 2; the settlement is idempotent). If it still fails, or any other step of the settlement fails (for example the
side-effect outbox insert, which rolls the payment change back with it), nothing is committed and the endpoint answers
`500` so Paystack redelivers the event; the customer's verify poll (`GET /payment/verify`) and, for an unpaid order,
the expiry job remain the other ways it gets settled.

Each attempt's lock waits are bounded (`shopfi.webhook.lock-timeout-ms`, 8 s per wait), and all attempts of one
delivery share one budget (`shopfi.webhook.settlement-deadline-ms`, 20 s, under Paystack's 30 s timeout): no attempt
starts with less than 250 ms left, and each attempt's lock timeout is capped to what is left. Past the budget the
answer is `500` (redelivered; the settlement is idempotent).

EVT-01c rework 1, **`503` when busy.** At most `shopfi.payments.webhook.max-concurrent-settlements` (default 3)
settlements run at once, so a burst of webhooks waiting for the same locked rows cannot take the whole connection pool.
A signed webhook that needs a settlement (`charge.success` / `charge.failed`) and finds no free slot within 50 ms
(`shopfi.payments.webhook.permit-wait-ms`, at most 100 ms) is answered `503` with the plain body `Busy` and no details;
nothing was applied and Paystack redelivers it. The signature is checked first (a bad one is still `403`), and an event
that can never be applied is still acknowledged `200` without needing a slot.

| webhook status | meaning |
|---|---|
| 200 `Processed` | applied and committed, or can never be applied (ignored) |
| 403 | signature invalid |
| 404 | unknown / inactive webhook token |
| 500 `Error` | not applied (settlement failed, retries exhausted or deadline passed); redelivered |
| 503 `Busy` | not applied, all settlement slots busy; redelivered |

**Payment-success side effects (API-04 rework 3).** The customer receipt e-mail, the admin "payment successful" e-mail
and the admin in-app / WebSocket notification are sent only after the settling transaction COMMITS, once per settled
payment. An attempt that is rolled back and retried (deadlock, `COUPON_BUSY` at commit) sends nothing, and if every
attempt fails no receipt goes out for the unrecorded payment; the later settlement (verify poll, a redelivered event)
sends it once. Duplicate success events for an already settled payment send nothing.

## Error codes

| HTTP | `data.code` | Meaning |
|---|---|---|
| 400 | `INVALID_REFERENCE` | malformed `reference` |
| 400 | `IDEMPOTENCY_KEY_INVALID` | bad `Idempotency-Key` |
| 400 | `INVALID_SORT` | unsupported sort property |
| 400 | `PRODUCT_UNAVAILABLE` | (PAY-03 rework, additive: the same 400 and `description` as before, "One or more products in your cart are no longer available for ecommerce purchase.") a cart product is deactivated or not listed for e-commerce. Nothing is created or reserved; an open order is not touched (offer cancel) |
| 400 | `COUPON_PER_USER_LIMIT` | (API-06) checkout or `GET /tax?couponCode=`: the customer already holds `perUserUsageLimit` live redemptions of this coupon (orders that are not cancelled / failed / expired). `description` "You have already used this coupon the maximum number of times". The cart preview reports the same code as `coupon.reason`. Nothing is reserved |
| 400 | none | (coupon create `POST /api/v1/coupon/add-coupon` and update `PUT /api/v1/coupon/{id}`) `usageLimit` or `perUserUsageLimit` is not a JSON whole number. `description` is `"<field> must be a whole number"` for a decimal (`1.5`, `1E999999999`), `"<field> must be a whole number (a JSON number, not text)"` for a string (`"7"`), `"<field> is out of range"` for an integer outside 32-bit range (`99999999999`); `data` is null and the submitted value is never echoed. Nothing is stored |
| 400 | none | (coupon create / update) a field rule is broken: `description` `"Validation failed"`, `data` maps field to message, e.g. `{"usageLimit": "Usage limit cannot exceed 1000000"}`, `{"perUserUsageLimit": "Per-user usage limit cannot exceed 1000000"}`, `{"perUserLimitWithinTotal": "Per-user usage limit cannot exceed the total usage limit"}`. Limits in "Coupon limits" below |
| 404 | `PAYMENT_NOT_FOUND` | unknown, other customer's or other tenant's reference |
| 404 | `ORDER_NOT_FOUND` | same for orders |
| 404 | `DELIVERY_DETAIL_NOT_FOUND` | address not the caller's |
| 409 | `ORDER_NOT_CANCELLABLE` | order already paid/failed |
| 409 | `IDEMPOTENT_ORDER_CLOSED` | keyed order is no longer payable |
| 409 | `OPEN_ORDER_EXISTS` | (PAY-03) the customer already has an open (unpaid, not expired) order for a different cart / coupon / address, or for this cart but it cannot be resumed (no stored Paystack URL); `data.orderNumber`, `data.expiresAt` (ISO-8601 UTC). Nothing was created or reserved; pay (order detail `authorizationUrl`) or cancel that order, then retry |
| 422 | `IDEMPOTENCY_KEY_REUSED` | key reused for a different request |
| 429 | none | too many coupon previews (`Retry-After`) |
| 503 | `COUPON_BUSY` | the coupon row stayed locked past the wait limit (5 s) while checkout reserved a redemption or a cancel (owner / admin) / verify poll gave one back or took it again; nothing changed, retry. `Retry-After: 1` (rework 3). The webhook's own `503` is the plain-text `Busy` (see above), never `COUPON_BUSY` |
| 503 | `TRY_AGAIN` | (API-04 rework 2) a concurrency conflict that survived 3 in-process attempts: deadlock victim (40P01), serialization failure (40001) or, on cancel / admin status / verify, an optimistic-lock conflict with a webhook or cancel touching the same product. Rolled back, nothing changed; `Retry-After: 1`. Never a 500. API-05: also any optimistic-lock failure (`ObjectOptimisticLockingFailureException`) on any endpoint, including a version conflict while a stock writer takes the product locks (rework: that one used to surface as JPA's own exception, `409`); checkout itself no longer produces one (see "Concurrent checkouts" below) |

Other 400s (empty cart, invalid coupon, no address, out of stock) keep their existing `description` text and have no code
(an unavailable product now has `PRODUCT_UNAVAILABLE`, above).
"Invalid coupon" stays uncoded on purpose (unknown, inactive, not started, expired and used up are not
told apart by a code); only the per-user limit has one.
Out of stock is `400` with `description` `"Insufficient stock for: <product name>"`.

**Concurrent checkouts of the same product (API-05).** Before API-05 two checkouts of one product both read the same
inventory layers (no version, no lock), both decremented them and the second commit failed on `Product.version` with a
`500`. Now every writer of stock (checkout reservation, cancel / expiry / `charge.failed` release, payment
finalisation, merchant restock, late-payment re-reservation, walk-in sale, receipt, adjustment, conversion) first locks
the products it touches with ONE `SELECT ... ORDER BY id FOR NO KEY UPDATE` (ids ascending, so multi-product carts in
any order cannot deadlock; `NO KEY` so it does not wait for the FK key-share locks that other in-flight orders hold on
the product) and re-reads them and their layers under that lock. Checkout takes it as its LAST step, after the Paystack
call:

1. customer row lock (ORD-01), replays, pricing, order + payment rows;
2. a plain (unlocked) stock check: a cart that visibly cannot be served is refused `400` before Paystack is called;
3. Paystack initialise (no product lock held, so checkouts of the same product run their Paystack calls in parallel);
4. product row locks in id order, stock reservation from the layers, commit.

Result: `N` customers buying the last `K < N` units at once get exactly `K` orders and `N-K` clean `400` "Insufficient
stock", stock ends at `0`, `soldCount` counts only paid units (the product UPDATE writes only changed columns, so a
stock change never overwrites `soldCount` / `viewCount` / `addToCartCount`), and no request gets a `500`. A
customer who loses the race in step 4 has had a Paystack transaction initialised that is never shown to anyone (the
order rolls back; nothing can be paid against it). Checkout is NOT retried in-process (a retry would initialise a
second Paystack transaction); any remaining conflict is the `503 TRY_AGAIN` above, and the same `Idempotency-Key` can
be re-sent. A coupon redemption reserved for a checkout that ends in "Insufficient stock" is given back exactly once.

**Coupon redemptions (API-04 rework).** Checkout reserves one redemption of the coupon with an atomic conditional
update in its own short transaction that commits before the checkout transaction starts, so checkout never holds a
coupon row lock across the Paystack call (it holds the customer row across it, ORD-01; since API-05 it locks its
product rows only after the call, for the short stock reservation before commit); the
reservation is given back when the checkout does not create an order with it (rejection, Paystack failure). Refusals
that do not need the usage counter (address, empty cart, unavailable items, idempotent replays, sale items, unknown
code, per-user limit, minimum amount) are decided before reserving (rework 2), so they never hold a redemption. An
order that becomes CANCELLED (customer, admin cancel paid or unpaid (ORD-02b), expiry) or FAILED (`charge.failed`) gives its redemption
back exactly once; a late payment that revives it takes it again. The per-user limit counts the customer's orders that
still hold a redemption. See `DISCOUNTS_FRONTEND_INTEGRATION.md` section 8.

**Lock order (API-04 rework 2): order -> stock -> coupon, coupon row LAST.** Cancel (owner, admin, expiry, straggler),
`charge.failed`, a late payment after a merchant cancel and a late-payment revival lock the order row first, then write
the order, payment, stock and product rows, and change the coupon counter with ONE `UPDATE` as the very last statement
before `COMMIT` (deferred to the transaction's before-commit phase, after a flush of everything else). So none of them
holds the coupon row while it waits for product / stock rows, and two of them can no longer deadlock on coupon vs stock
(they all lock in the same order). API-05: the "stock" step is always the product rows in ascending id order, in one
`FOR NO KEY UPDATE` statement, taken before any inventory layer is read and before ANY product row is written
(stock refresh or sold-count `UPDATE`); checkout takes it after its Paystack call. Rework: payment success
(webhook / verify) bumps the sold counts only after the stock finalisation has taken the order's product locks (before,
its per-item sold-count `UPDATE`s locked the products in order-item order and deadlocked with a checkout of the same
products, which then answered `503 TRY_AGAIN` after Paystack had been called); merchant restock and walk-in sale do the
same. The admin inventory summary / history GETs bootstrap a legacy balance into its layer only under that lock too (a
product that already has a layer is read without any lock). Full order: customer row (checkout) | order row (cancel /
webhook / expiry) -> product rows by id -> coupon row last. A late payment's re-reservation runs in its own short
transaction under the order lock and takes the product locks there. Admin edit / deactivate lock only the coupon
(`FOR NO KEY UPDATE`, which does not conflict with the FK lock a checkout's new order holds); delete locks the coupon's
orders first, then the coupon. Every coupon wait is bounded (5 s, `503 COUPON_BUSY`).

**Cart lock order (CAT-04): cart row, then its lines.** Every cart writer locks the customer's cart row before it reads
or writes a line: add-item (also each item of populate), update quantity and remove line take it `FOR NO KEY UPDATE`;
clear cart and payment success (webhook / verify) take it `FOR UPDATE`, then delete EVERY line of that cart in one
statement (`DELETE FROM cart_items WHERE cart_id = ?`), then the cart row. Before, clear and settlement deleted only the
lines they had loaded and the cart row last, the reverse of add-item: an add-item of a product already in the cart
deadlocked with them (`503 TRY_AGAIN` or a settlement retry), and a line added in between made the cart delete fail on
`fk_cart_items_on_cart` (clear `409`, webhook `500` until Paystack redelivered). Now a clear / settlement racing an
add-item waits for it and removes its line too; an add-item arriving after the cart was deleted starts a new cart.
Checkout only READS the cart (no cart lock: it would be held across the Paystack call) under its customer row lock.
Global order, no path inverts it: customer row (checkout) -> order row (settlement, cancel, expiry) -> payment row ->
cart row -> cart lines -> product rows by id -> location ledger -> ledger accounts by id -> coupon row last. No
transaction that holds a cart row lock takes a customer, order or payment lock afterwards (add-item's first-cart insert
needs the customer row's FK share lock, but it takes it before it holds any lock).

## Coupon limits (`POST /api/v1/coupon/add-coupon`, `PUT /api/v1/coupon/{id}`)

| Field | Rule | 400 message (`data.<field>`) |
|---|---|---|
| `usageLimit` | required JSON integer, 1 to 1,000,000 | "Usage limit must be at least 1" / "Usage limit cannot exceed 1000000" |
| `perUserUsageLimit` | optional JSON integer (omit or `null` = no per-customer limit), 1 to 1,000,000 (`@Max(1000000)`) AND at most `usageLimit` | "Per-user usage limit must be at least 1" / "Per-user usage limit cannot exceed 1000000"; above `usageLimit`: key `perUserLimitWithinTotal`, "Per-user usage limit cannot exceed the total usage limit" |

Both integers are strict (no `1.5`, no `"7"`, no coercion): see the whole-number row in "Error codes". Since
`perUserUsageLimit` may not exceed `usageLimit`, which is itself capped at 1,000,000, a value above 1,000,000 fails both
rules at once (two keys in `data`). A form should cap the input at 1,000,000 and at the total limit.

## Shop-wide discount, admin reads (API-06)

All `hasRole('ADMIN')`. Every response row is `{id, label, discountPercentage, startAt, endAt, isActive,
currentlyActive, status}`; `status` (new, API-06) is `SCHEDULED` (switched on, starts later), `ACTIVE` (switched on and
inside its window; prices use it), `EXPIRED` (switched on, window over) or `INACTIVE` (switched off: ended by the admin
or replaced by a newer discount).

* `GET /api/v1/admin/shop-discount` (new): the discounts that are switched on and have not ended, in force now OR
  scheduled for later, soonest `startAt` first; `[]` when none (never 404). At most 50 rows; setting a discount retires
  the others, so normally there is 0 or 1. Use it to read a scheduled discount back (e.g. after a page reload) and end it
  with `DELETE /api/v1/admin/shop-discount/{id}` before it starts.
* `GET /api/v1/admin/shop-discount/current` (unchanged): only the discount in force right now, else 404.

## Payment settings webhook URL, shown once (API-06)

`GET /api/v1/payment-settings`, `PUT /api/v1/payment-settings` and `POST /api/v1/payment-settings/rotate-webhook-token`
(all `hasRole('ADMIN')`) return `{secretConfigured, publicKey, enabled, webhookUrl, hasWebhookToken, webhookUrlMasked}`.

* `webhookUrl`, the full URL to paste into Paystack (it contains the tenant's routing token), is returned ONLY by the call
  that minted the token: `POST /rotate-webhook-token` (always), or the `PUT` that saved the settings for the first time.
  It is `null` in every `GET` and in every later `PUT`.
* `hasWebhookToken`: whether a webhook URL exists.
* `webhookUrlMasked`: the URL with the token replaced by `****` plus its last 4 characters
  (`https://api.example.com/api/v1/webhook/****x9Qz`), so the admin can tell which URL is live; `null` when there is none.
* A lost URL cannot be read back: rotate to get a new one (the old URL stops working at once; paste the new one into
  Paystack). The token is never logged.
