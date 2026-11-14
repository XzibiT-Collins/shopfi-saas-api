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
* Without a key, the customer's still-open (INITIATED, not expired) order for the very same cart + coupon + address is
  reused (also `idempotentReplay: true`), so a double submit or Back button never creates a second order.
* Concurrent requests of one customer are serialised server side; failed initialisations leave nothing behind, so the
  same key can be retried.

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
`COUPON_MIN_AMOUNT`, `COUPON_NOT_COMBINABLE` (cart has discounted items). Empty cart: 400.
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
`orderDate` is a zone-less server-local timestamp (legacy field); use `expiresAt` for anything time-critical.
`my-orders` and `/all` sorts only by `createdAt`, `orderNumber`, `status`, `deliveryStatus`, `id`
(`?sort=createdAt,desc`); any other sort property is 400 `INVALID_SORT`.

## Webhook behaviour (`POST /api/v1/webhook/{token}`, unchanged routing)

The per-tenant token selects the tenant, the `X-Paystack-Signature` HMAC-SHA512 is verified with that tenant's secret
(constant-time compare). Then, per event type:

* `charge.success`: completes the payment. Amount and currency are required and must match the order; an event without them is ignored (fail closed) and the customer's verify poll settles it from the provider's own data. Idempotent.
  A late success for an order that is FAILED or CANCELLED/expired re-reserves the stock and completes it; if the stock
  is gone the payment is recorded COMPLETED with `refundRequired` and the order stays closed. Any other failure while re-reserving (database/pool timeout) is not treated as out of stock: nothing is changed and the next webhook or verify poll retries.
* `charge.failed`: the only event that marks the payment/order FAILED and releases the stock. Never overrides COMPLETED.
* any other event type: ignored.

## Error codes

| HTTP | `data.code` | Meaning |
|---|---|---|
| 400 | `INVALID_REFERENCE` | malformed `reference` |
| 400 | `IDEMPOTENCY_KEY_INVALID` | bad `Idempotency-Key` |
| 400 | `INVALID_SORT` | unsupported sort property |
| 404 | `PAYMENT_NOT_FOUND` | unknown, other customer's or other tenant's reference |
| 404 | `ORDER_NOT_FOUND` | same for orders |
| 404 | `DELIVERY_DETAIL_NOT_FOUND` | address not the caller's |
| 409 | `ORDER_NOT_CANCELLABLE` | order already paid/failed |
| 409 | `IDEMPOTENT_ORDER_CLOSED` | keyed order is no longer payable |
| 422 | `IDEMPOTENCY_KEY_REUSED` | key reused for a different request |
| 429 | none | too many coupon previews (`Retry-After`) |

Other 400s (empty cart, invalid coupon, no address, out of stock) keep their existing `description` text and have no code.
