# Admin metrics API (METRICS-BE: API-RD-02 metrics part, API-RD-10, API-RD-05, API-RD-01)

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

Not covered here (documented elsewhere or unchanged): `/coupons`, `/low-stock-products`, `/top-customers`,
`/customers/{userId}`, `/customers/{userId}/orders`; all of them are rows in [authorization-matrix.md](authorization-matrix.md).

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
