# Admin metrics API (METRICS-BE: API-RD-02 metrics part, API-RD-10, API-RD-05, API-RD-01, API-RD-02 remainder)

Read-only dashboards for the shop owner. Base path `/api/v1/admin/metrics`. Every endpoint is `ADMIN` only
(`@PreAuthorize("hasRole('ADMIN')")` on the controller): anonymous gets `401`, `CUSTOMER` and `FRONT_DESK` get `403`.
All of them run inside the tenant resolved by the tenant filter (schema per tenant): a product, customer or order of
another tenant does not exist as far as the caller can tell, so a foreign id answers `404`, never another tenant's data.
Money fields are strings in the form `GHS 220.00`; counts and ids are numbers. Error bodies are
`{"description": <text>, "data": ...}` (see [errors.md](errors.md)); branch on the status.

| Method | Path | Purpose |
|---|---|---|
| GET | `/dashboard-metric` | headline numbers for the home screen |
| GET | `/top-products` | the six best-selling products with engagement counters |
| GET | `/products/{productId}` | the same counters for ONE product (API-RD-01) |
| GET | `/site-metric?from&to` | visits and the visit-to-order rate |
| GET | `/sales-analytics?source&granularity&from&to` | revenue, profit, order mix over time |
| GET | `/customers?search&sortBy&direction&page&size` | customer list with order totals (API-RD-10, API-RD-05) |
| GET | `/customers/{userId}` | one customer with addresses and totals (API-RD-02) |
| GET | `/customers/{userId}/orders?page&size` | that customer's orders, paged (API-RD-02) |
| GET | `/coupons` | coupon list with redemption totals (API-RD-02) |
| GET | `/low-stock-products` | the ten products closest to (or under) their low-stock threshold (API-RD-02) |
| GET | `/top-customers` | the ten best customers by completed orders (API-RD-02) |

The last five are described at the end of this file ("Customer detail, coupons, low stock, top customers").

## GET /dashboard-metric

No parameters.

```json dashboard-response
{
  "description": null,
  "data": {
    "totalRevenue": "GHS 12450.50",
    "orderCountMetric": { "totalOrders": 120, "totalDeliveredOrders": 80, "totalPendingOrders": 25, "totalCancelledOrders": 5 },
    "totalCustomers": 64,
    "totalProducts": 31,
    "totalSiteVisits": 980,
    "dailyRevenueMetric": [ { "date": "2026-11-22", "revenue": 310.00 } ],
    "top5Compositions": [
      { "productId": 12, "productName": "Oud Noir 50ml", "productImage": "https://cdn.example.com/oud.jpg", "totalSold": 40, "totalRevenue": 2000.00 }
    ]
  }
}
```

`totalRevenue` and the daily series count COMPLETED (paid) online orders; the series is the last 7 days including today
(days without revenue are absent). `totalCustomers` counts every user except ADMIN (front-desk staff included).
`totalSiteVisits` is the all-time number of unique visitors.

## GET /top-products

No parameters. At most six rows, best `soldCount` first (ties by product id). Each row is a `MostPurchaseProductResponse`,
the shape and definitions shared with `/products/{productId}`:

| Field | Meaning |
|---|---|
| `productId`, `productName` | the product |
| `viewCount` | product page views counted so far (deduplicated and rate limited when counted); NULL counts as 0 |
| `addToCartCount` | times the product was added to a cart; NULL counts as 0 |
| `soldCount` | units sold (online and walk-in) |
| `conversionRate` | percent, 2 decimals, HALF_UP: `soldCount / addToCartCount * 100`, `0` when `addToCartCount` is 0 (it is NOT sold / views) |

```json top-products-response
{
  "description": null,
  "data": [
    { "productId": 12, "productName": "Oud Noir 50ml", "viewCount": 200, "addToCartCount": 8, "soldCount": 2, "conversionRate": 25.00 }
  ]
}
```

## GET /products/{productId}

Engagement counters of one product (API-RD-01): same fields, same definitions and the same rounding as one
`/top-products` row (a test asserts the two are equal). Works for any product of the tenant, active or not, so the
frontend is no longer limited to the top six.

Query parameters `from` and `to` (`yyyy-MM-dd`) are accepted: send both or neither, `from` must not be after `to`
(otherwise `400`). **They do not filter the numbers.** The counters are lifetime totals kept on the product row; no
per-day event history exists to slice (documented default, see "Open items" in the PR). Do not label the figures as
"in the selected range".

| Case | Answer |
|---|---|
| product exists, no activity (or NULL counters) | `200`, all counts `0`, `conversionRate` `0` |
| unknown id, or an id that exists only in another tenant | `404` `Product not found` |
| `productId` not a number | `400` |
| only one of `from` / `to`, or `from` after `to`, or unparsable date | `400` |

```json product-response
{
  "description": null,
  "data": { "productId": 12, "productName": "Oud Noir 50ml", "viewCount": 200, "addToCartCount": 8, "soldCount": 2, "conversionRate": 25.00 }
}
```

## GET /site-metric

`from` and `to` (`yyyy-MM-dd`, optional). Send both or neither; with neither the window is 2020-01-01 to today.

```json site-metric-response
{
  "description": null,
  "data": {
    "totalVisits": 1500,
    "totalUniqueVisitors": 980,
    "totalPageViews": 4200,
    "uniqueVisitToOrderConversionRate": 12.24,
    "top5MostVisitedPages": [ { "page": "/product/oud-noir", "uniqueVisits": 310 } ]
  }
}
```

`uniqueVisitToOrderConversionRate` is `all orders ever placed / unique visitors in the window * 100` (the order count is
not restricted to the window), `0` with no visitors.

## GET /sales-analytics

| Parameter | Values | Default |
|---|---|---|
| `source` | `ALL`, `ONLINE`, `WALK_IN` | `ALL` |
| `granularity` | `DAY`, `WEEK`, `MONTH`, `YEAR` | `DAY` |
| `from`, `to` | `yyyy-MM-dd`, both or neither, `from` <= `to` (else `400`) | last 7 days / 8 weeks / 12 months / 5 years by granularity |

Figures come from the journal (revenue, cost of goods) and the expense categories; a cancelled paid online order is netted
out (`SALE_REVERSAL`). The previous period of equal length feeds the `trend` of each mini stat. Bucket `name`: weekday
short name (DAY), `MMM d` (WEEK), month short name (MONTH), year (YEAR).

```json sales-analytics-response
{
  "description": null,
  "data": {
    "revenueBreakdown": [ { "name": "Mon", "revenue": 310.00 } ],
    "profitBreakdown": [ { "name": "Mon", "grossProfit": 120.00, "netProfit": 80.00 } ],
    "miniStats": [ { "label": "Total Revenue", "value": "GHS 310.00", "trend": "12%", "isUp": true } ],
    "orderStatus": [ { "name": "DELIVERED", "value": 3 } ]
  }
}
```

`miniStats` labels, in order: `Avg Order Value`, `Total Revenue`, `Total Orders`, `Total Gross Profit`,
`Total Net Profit`.

## GET /customers

The customer list of the admin Customers page (API-RD-10, API-RD-05). Page numbers are zero-based in the request
(`page=0`), one-based in the response (`number`).

| Parameter | Meaning | Default / limits |
|---|---|---|
| `search` | customers whose full name OR e-mail contains the text, case-insensitive. `%`, `_` and `\` in the text are literal characters, not wildcards. Blank = no filter | at most **100** characters after trimming, longer answers `400` |
| `sortBy` | `orders` (order count), `totalSpent`, `lastOrder` (date of the newest order); `ORDERS`, `TOTAL_SPENT`, `LAST_ORDER` also accepted | `orders`; anything else `400` |
| `direction` | `asc` or `desc` (any case) | `desc`; anything else `400` |
| `page`, `size` | paging | `size` is capped at **50** (a larger value is lowered to 50, never an error), unpaged requests use 20 |

Order of rows: the `sortBy` key in the requested direction, customers without any order last (for `lastOrder`), and the
customer `id` ascending as the tie-breaker, so equal values keep one stable order and consecutive pages never repeat or
skip a customer. A `sort=` parameter of the old Spring paging style is ignored (as before), it does not fail.

**Who is listed (decision, API-RD-10):** every registered `CUSTOMER` of the tenant, including customers who never ordered
(`orderCount` 0, `totalSpent` `GHS 0.00`, `lastOrderDate` null). Before, the list was a join over orders and silently
omitted them. Staff accounts (`ADMIN`, `FRONT_DESK`) are not listed. Walk-in sales are separate records and are not part of
these totals.

| Field | Meaning |
|---|---|
| `orderCount` | all orders of the customer, any status (unchanged) |
| `totalSpent` | COMPLETED orders that were not cancelled or returned (unchanged) |
| `lastOrderDate` | **new, additive**: calendar date (`yyyy-MM-dd`) of the newest order of any status, `null` when none |

The response is backward compatible: the previous fields keep their names and meaning, `lastOrderDate` is the only new field.

```json customers-response
{
  "description": null,
  "data": {
    "content": [
      { "id": 41, "fullName": "Ama Mensah", "email": "ama@example.com", "orderCount": 2, "totalSpent": "GHS 100.00",
        "isActive": true, "dateJoined": "2026-01-15", "lastOrderDate": "2026-03-04" }
    ],
    "totalPages": 1, "totalElements": 1, "size": 20, "number": 1, "numberOfElements": 1,
    "isFirst": true, "isLast": true, "isEmpty": false
  }
}
```

### Cost and abuse limits

* No migration: the list aggregates `orders` by customer (one grouped scan, the join uses the leading `user_id` of the
  existing `orders` unique index on `(user_id, idempotency_key)`) and a `LIKE '%text%'` scan of `users`. At the shop sizes
  this platform serves that is a single-digit-millisecond read; a trigram index is the next step if a shop reaches
  hundreds of thousands of customers.
* Denial of service is bounded by the page size cap (50 rows), the 100 character search cap and the sort whitelist (the sort
  expression is chosen from an enum, user text only ever travels as a bind parameter).
* The search text is never logged and never put in a URL by the server; note that it is a query parameter, so it can reach
  access logs of a proxy in front of the API (staff-typed customer names / e-mails): keep these logs restricted.

## Customer detail, coupons, low stock, top customers (API-RD-02 remainder)

Same rules as above: `ADMIN` only, tenant scoped, envelope `{"description", "data"}`. They are read-only and write nothing.
These five routes existed before; this section documents what they already return, nothing changed in behaviour.

### GET /customers/{userId}

`userId` is a number; anything else answers `400` (`Invalid parameter type`). An id that does not exist in this tenant
(including an id that only exists in another tenant) answers `404` `User not found`.

**Not limited to customers (documented, unchanged):** the lookup is by user id only, so the id of an `ADMIN` or `FRONT_DESK`
account of the same tenant also answers `200` with that account's data. The list (`GET /customers`) never shows staff, so
the UI only reaches this route with customer ids it got from the list.

| Field | Meaning |
|---|---|
| `id`, `fullName`, `email` | the account |
| `dateJoined` | account creation, zone-less server-local `yyyy-MM-ddTHH:mm:ss` |
| `isActive` | the account's active flag |
| `addresses` | the customer's saved delivery details (`DeliveryDetailResponse`, same shape as `GET /delivery-detail`); `[]` when none |
| `totalSpent` | `GHS 0.00` style string: COMPLETED orders that were not cancelled or returned (same rule as the list) |
| `orderCount` | all orders of the customer, any status |

```json customer-detail-response
{
  "description": null,
  "data": {
    "id": 41, "fullName": "Ama Mensah", "email": "ama@example.com", "dateJoined": "2026-01-15T09:30:00", "isActive": true,
    "addresses": [
      { "id": 7, "recipientName": "Ama Mensah", "phoneNumber": "0240000000", "alternatePhoneNumber": null,
        "addressLine1": "12 Palm Street", "addressLine2": null, "city": "Accra", "region": "Greater Accra",
        "landmark": "Opposite the pharmacy", "label": "HOME", "isDefault": true }
    ],
    "totalSpent": "GHS 100.00", "orderCount": 2
  }
}
```

The response carries a name, e-mail, phone numbers and street addresses: PII. The route never puts them in a URL or a log.

### GET /customers/{userId}/orders

Paged list of the customer's orders (`PageResponse`, the same envelope as `GET /customers`: `content`, `totalPages`,
`totalElements`, `size`, `number` (**one-based**), `numberOfElements`, `isFirst`, `isLast`, `isEmpty`). Each row is the
`OrderListResponse` of `GET /order/all` ([orders.md](orders.md)).

| Parameter | Meaning | Default / limits |
|---|---|---|
| `page`, `size` | paging, `page` zero-based in the request | `size` default 20; the dev and prod profiles cap it at **50** (a larger value is lowered, never an error) |
| `sort` | Spring paging sort, e.g. `createdAt,desc`. There is **no default order** (the database order is not guaranteed), so send a sort for a stable listing | an unknown property answers `400` with `data.code` `INVALID_SORT` |

An unknown `userId` answers `404` `User not found`; a customer with no orders is `200` with an empty `content`. Money is the
`GHS 220.00` string, `orderedAt` is the UTC instant, `orderDate` is deprecated (see orders.md).

```json customer-orders-response
{
  "description": null,
  "data": {
    "content": [
      { "orderId": 301, "orderNumber": "ORD-20260304-0001", "reference": "ref_abc123", "refundRequired": false,
        "cancellable": false, "paymentStatus": "COMPLETED", "deliveryStatus": "DELIVERED", "totalAmount": "GHS 100.00",
        "orderDate": "2026-03-04T10:15:00", "orderedAt": "2026-03-04T10:15:00Z", "version": 3 }
    ],
    "totalPages": 1, "totalElements": 1, "size": 20, "number": 1, "numberOfElements": 1,
    "isFirst": true, "isLast": true, "isEmpty": false
  }
}
```

### GET /coupons

No parameters, **not paged**: `coupons` holds every coupon of the tenant (active or not), so the response grows with the
coupon count (a shop has tens, not thousands). The totals are over COMPLETED payments only.

| Field | Meaning |
|---|---|
| `totalCreated` | the number of coupons in `coupons` |
| `totalUsage` | number of such orders that used a coupon |
| `totalDiscountGiven`, `totalRevenueGenerated` | `GHS 0.00` strings, sums over those orders (`totalRevenueGenerated` is the order totals, discount already taken off) |
| `coupons[]` | `CouponListResponse`: `couponId`, `couponCode`, `isActive`, `discountType` (`PERCENTAGE` or `FLAT`), `usageLimit`, `usageCount` (live redemptions, see checkout.md, so it can differ from `totalUsage`), `discountValue`, `maximumDiscountAmount`, `minimumCartAmountForDiscount`, `perUserUsageLimit`, `startDate`, `expirationDate` (`yyyy-MM-dd`) |

Nullable: `usageLimit`, `maximumDiscountAmount`, `minimumCartAmountForDiscount`, `perUserUsageLimit`, `startDate`,
`expirationDate` (no limit / no window). With no coupon at all the totals are `GHS 0.00` / `0` and `coupons` is `[]`.

```json coupons-response
{
  "description": null,
  "data": {
    "totalCreated": 1, "totalUsage": 4, "totalDiscountGiven": "GHS 40.00", "totalRevenueGenerated": "GHS 360.00",
    "coupons": [
      { "couponId": 5, "couponCode": "WELCOME10", "isActive": true, "discountType": "PERCENTAGE", "usageLimit": 100,
        "usageCount": 4, "discountValue": 10.00, "maximumDiscountAmount": 20.00, "minimumCartAmountForDiscount": 50.00,
        "perUserUsageLimit": 1, "startDate": "2026-01-01", "expirationDate": "2026-12-31" }
    ]
  }
}
```

### GET /low-stock-products

At most **10** rows, the products whose `stockQuantity` is at or below their own `lowStockThreshold`, lowest stock first
(ties in database order). Not paged, no parameters. `productImage` may be `null`. Stock here is the product's total
(`stockQuantity`), not a storage location's; per-location stock is `GET /admin/inventory/products/{id}/stock-by-location`.

```json low-stock-response
{
  "description": null,
  "data": [
    { "id": 12, "productName": "Oud Noir 50ml", "productImage": "https://cdn.example.com/oud.png", "stockQuantity": 2 }
  ]
}
```

### GET /top-customers

At most **10** rows, ordered by order count then total spent (both descending). Counted: COMPLETED payments whose order is
not CANCELLED or RETURNED (the same rule as `totalSpent`). Customers without such an order are not listed. The row has **no
id**, only name and e-mail (PII), so it cannot be linked to `/customers/{userId}` directly: match on `email` against the list.
`totalAmountSpent` is a plain **number** (major units, currency GHS), unlike the `GHS 100.00` strings elsewhere.

```json top-customers-response
{
  "description": null,
  "data": [
    { "fullName": "Ama Mensah", "email": "ama@example.com", "totalOrders": 2, "totalAmountSpent": 100.00 }
  ]
}
```
