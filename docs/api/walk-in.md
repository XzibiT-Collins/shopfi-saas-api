# Walk-in sales API (WALKIN-BE: API-RD-02 walk-in part, API-RD-03 AC1+AC2, API-RD-13)

The register (front desk / admin) sells over the counter. Base path `/api/v1/admin/walk-in`. All endpoints run inside the
tenant resolved by the tenant filter (schema per tenant): products, customers, orders and idempotency keys of another
tenant do not exist as far as the caller can tell.

Roles: `ADMIN`, or `FRONT_DESK` with the matching front-desk permission (`WALK_IN_ORDER_CREATE` for quote and sale,
`CUSTOMER_SEARCH` for customer search, `WALK_IN_ORDER_VIEW` for reads, `WALK_IN_ORDER_MARK_RECEIPT_PRINTED`). Other roles
get `403`. Money fields are strings in the form `GHS 220.00`; ids and counts are numbers. Error bodies are
`{"description": <text>, "data": {"code": <stable code>}}` (see [errors.md](errors.md)); branch on status and `data.code`.

| Method | Path | Purpose |
|---|---|---|
| POST | `/quote` | price a draft sale, writes nothing (AC1) |
| POST | `/order` | record the sale, optional `Idempotency-Key` (AC2) |
| POST | `/customers/search` | find registered customers, search text in the body (RD-13b) |
| GET | `/customers/search?query=` | **deprecated**, same result, text in the URL (RD-13b) |
| GET | `/orders`, `/orders/{orderNumber}` | list / read walk-in orders |
| PATCH | `/orders/{orderNumber}/receipt-printed` | mark the receipt printed |

Cancel and refund (API-RD-03 AC3) are NOT part of this contract yet. `CANCELLED` / `REFUNDED` exist as order statuses
only; a later release adds the endpoints (see "Cancel / refund readiness").

## POST /quote

Prices a draft with exactly the code the sale uses (same effective product / shop-wide prices, no-stacking rule,
discount rounding, active taxes), so a quote and the sale of the same draft agree to the cent while prices and taxes do
not change in between. It runs in a read-only transaction: no stock, sold count, ledger entry, order or customer row is
written (a test counts rows and stock before and after). Customer fields are deliberately not accepted: pricing does not
depend on the buyer and a quote must not carry PII.

```json quote-request
{
  "items": [{ "productId": 12, "quantity": 2 }],
  "discountType": "PERCENTAGE",
  "discountValue": 10,
  "paymentMethod": "CASH",
  "amountPaid": 500.00
}
```

`items` is required and non-empty. `discountType` + `discountValue` go together (manual discount; not allowed when an
item is already on sale). `paymentMethod` and `amountPaid` are optional.

```json quote-response
{
  "description": null,
  "data": {
    "items": [
      { "productId": 12, "productName": "Oud Noir 50ml", "sku": "OUD-50", "quantity": 2, "unitPrice": "GHS 50.00", "totalPrice": "GHS 100.00" }
    ],
    "originalSubtotal": "GHS 100.00",
    "automaticDiscountAmount": "GHS 0.00",
    "subtotal": "GHS 100.00",
    "discountType": "PERCENTAGE",
    "discountValue": "10.00%",
    "discountAmount": "GHS 10.00",
    "taxes": [
      { "taxName": "VAT", "taxCode": "VAT", "taxRate": 10.00, "taxableAmount": "GHS 90.00", "taxAmount": "GHS 9.00" }
    ],
    "totalTaxAmount": "GHS 9.00",
    "totalAmount": "GHS 99.00",
    "paymentMethod": "CASH",
    "amountPaid": "GHS 500.00",
    "changeGiven": "GHS 401.00",
    "balanceDue": "GHS 0.00"
  }
}
```

* `originalSubtotal` = gross before the automatic (product / shop-wide) discount; `automaticDiscountAmount` = that
  discount; `subtotal` = net of it, before the manual discount; `discountAmount` = the manual front-desk discount;
  `totalAmount = subtotal - discountAmount + totalTaxAmount`.
* `discountValue` is printed as `10.00%` for a `PERCENTAGE` discount and `GHS 5.00` for `FLAT` (`GHS 0.00` when there is
  none). Before WALKIN-BE a percentage was printed as money (`GHS 10.00`); the same fix applies to the order responses.
* `amountPaid`, `changeGiven`, `balanceDue` are `null` when the draft has no `amountPaid`. `changeGiven` is only
  computed for `CASH` (other methods never give change). Unlike the sale, a quote does NOT fail on an underpaid draft: it
  returns `balanceDue` (what is still owed) so the register can show it live.
* Errors, same as the sale: `400` empty items, inactive product, insufficient stock, bad or stacked discount; `404`
  unknown product; `403` role / permission. A quote is a snapshot: stock can still change before the sale.

## POST /order

Records the sale. Request body as today:

```json order-request
{
  "registeredUserId": null,
  "walkInCustomer": { "name": "Kofi Mensah", "phone": "0240000000", "email": null },
  "items": [{ "productId": 12, "quantity": 2 }],
  "discountType": "PERCENTAGE",
  "discountValue": 10,
  "paymentMethod": "CASH",
  "amountPaid": 500.00,
  "splitCashAmount": null,
  "splitMobileAmount": null
}
```

| Input | Where | Notes |
|---|---|---|
| `Idempotency-Key` | header, optional | 8-64 chars of `A-Za-z0-9._:-` (same format as checkout); else `400` `IDEMPOTENCY_KEY_INVALID` |

```json order-response
{
  "description": null,
  "data": {
    "orderNumber": "WI-20260101-0001",
    "customerName": "Kofi Mensah",
    "customerPhone": "0240000000",
    "processedBy": "Ama Front Desk",
    "discountType": "PERCENTAGE",
    "discountValue": "10.00%",
    "paymentMethod": "CASH",
    "status": "COMPLETED",
    "subtotal": "GHS 100.00",
    "originalSubtotal": "GHS 100.00",
    "automaticDiscountAmount": "GHS 0.00",
    "discountAmount": "GHS 10.00",
    "totalTaxAmount": "GHS 9.00",
    "totalAmount": "GHS 99.00",
    "amountPaid": "GHS 500.00",
    "changeGiven": "GHS 401.00",
    "receiptPrinted": false,
    "createdAt": "2026-01-01T10:15:30",
    "items": [
      { "productName": "Oud Noir 50ml", "sku": "OUD-50", "quantity": 2, "unitPrice": "GHS 50.00", "totalPrice": "GHS 100.00" }
    ],
    "idempotentReplay": false
  }
}
```

`idempotentReplay` appears only on this endpoint: `false` for the sale this request made, `true` when the answer is the
original sale of a repeated key.

### Idempotency semantics (mirrors the checkout contract)

* **Scope: tenant + cashier + key.** The tenant is the schema, so a key never matches another tenant's sale. Within a
  tenant the key belongs to the logged-in user that sent it (`processedBy`): two cashiers using the same key make two
  sales. The key is stored on the order (`idempotency_key`, unique per cashier) together with a SHA-256 fingerprint of
  the request body; the body itself is not kept.
* **Same key + same body** (line order, amount scale, blank vs missing customer fields are ignored): `200` with the
  ORIGINAL order, `idempotentReplay: true`. No second sale: no second stock decrement, sold-count increase, journal
  entry or customer row. A replay is decided before any validation, so it still answers after the product's stock or
  price changed. It returns the order's current state (once cancel exists, a replay of a cancelled sale shows
  `CANCELLED`; it never re-sells).
* **Same key + different body:** `409` `IDEMPOTENCY_KEY_REUSED`, nothing written. (Checkout answers `422` for its
  equivalent; the walk-in contract is `409` as specified by API-RD-03.)
* **Concurrent requests with one key:** serialised on the cashier's user row; exactly one sale is made and the others
  wait and receive the replay.
* **A failed sale leaves nothing behind**, so the same key can be retried after a validation error (`400`/`404`) or a
  ledger fault (`500 ACCOUNTING_ERROR`): the key is only consumed by a committed sale.
* **Retention:** no TTL. As in checkout (whose key lives on the order for as long as the order exists), the key stays
  with the sale permanently; reusing an old key with the same body returns that old sale, with another body is `409`.
  Clients generate a fresh random key per sale (e.g. a UUID when the cashier starts the sale) and re-send it unchanged
  on every retry, including a browser-level re-send.
* **Missing key:** accepted, behaves as before (each POST is a new sale, no replay protection), exactly like checkout
  where the header is optional. Reason: existing registers keep working until they send a key. Clients SHOULD always
  send one; the register is expected to.

### Errors

| Status | `data.code` | When |
|---|---|---|
| 400 | `IDEMPOTENCY_KEY_INVALID` | key not 8-64 chars of `A-Za-z0-9._:-` |
| 409 | `IDEMPOTENCY_KEY_REUSED` | key already used by this cashier for a different body |
| 500 | `ACCOUNTING_ERROR` | the ledger could not record the sale (API-RD-13a): the transaction rolled back, **nothing was saved or taken from stock**; retry with the same key. Before WALKIN-BE this answered `200` with an empty body. |
| 400 / 404 / 403 | none | validation, unknown product or customer, role (as before) |

## Customer search (API-RD-13b)

`POST /api/v1/admin/walk-in/customers/search`, permission `CUSTOMER_SEARCH`. The name or e-mail travels in the body, so
it never reaches access logs, browser history or referrers.

```json customer-search-request
{ "query": "ama@example.com" }
```

`query` is at most 100 characters; blank returns an empty list. Response `200`: `data` is an array of
`{ "id", "fullName", "email", "phone" }` (registered customers of this tenant only). The server does not log the text.

`GET /customers/search?query=` is **deprecated**: it answers the same data with `Deprecation: true` and a `Link`
header, and is kept only until the register moves to POST. New code must not use it.

## Cancel / refund readiness (API-RD-03 AC3, later)

Not implemented here, but the design leaves room: the order row keeps its key and fingerprint for life, status already
has `CANCELLED` / `REFUNDED`, and the replay path returns the order's current state. Cancel and refund will be new
endpoints under `/orders/{orderNumber}/...` taking their own `Idempotency-Key` with the same rules, restoring stock and
posting reversing journal entries in one transaction, so the failure behaviour above (rollback, `ACCOUNTING_ERROR`)
carries over.
