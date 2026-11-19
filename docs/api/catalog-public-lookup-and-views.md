# Public product lookup by ids and product view counting (CAT-07, VIEW-01)

Two storefront-facing contracts. Both are anonymous, tenant-scoped (the tenant is resolved from the request `Origin`
exactly like every other tenant route, see [cors.md](cors.md)) and answer the standard envelope of [errors.md](errors.md).

## 1. `GET /api/v1/product/by-ids?ids=1,2,3` (CAT-07)

Public lookup of specific products, for the storefront FeaturedProducts "manual" source (hand-picked ids). It replaces
walking `GET /product/listing` page by page.

| | |
|---|---|
| Auth | none (`permitAll` for GET and HEAD, like the other public catalog reads) |
| Query | `ids`: comma separated product ids, **1 to 12 entries**. Each entry is a positive integer of at most 18 digits (`[1-9][0-9]{0,17}`). Whitespace around an entry is tolerated. The parameter may also be repeated (`ids=1&ids=2`); the entries of all occurrences count together |
| Rows returned | only products that are **active and listed (enlisted)** in the caller's tenant. An id that does not exist, is inactive, is unlisted, was deleted or belongs to another tenant is silently left out (no 404, no per-id error: one cannot tell the cases apart) |
| Order | the order of the first occurrence of each id in `ids`; a repeated id appears once |
| Shape | `{"description": ..., "data": [PublicProductListing, ...]}` where each row is exactly the row of `GET /product/listing` (no stock quantity, no `isActive` / `isEnlisted`, effective price and discount computed) |
| Side effects | **none**: no view counted, no write, not recorded as a site visit beyond what any GET records |
| Caching | not cached (at most 12 primary-key rows in one query); `Cache-Control` as every API answer |

Errors (all `400`, `description` is fixed text, `data` is `null`):

| Case | `description` |
|---|---|
| `ids` missing or empty | `ids is required` |
| more than 12 entries (counted before removing repeated ids) | `At most 12 ids are allowed` |
| an entry that is empty, not a positive integer, zero, negative or longer than 18 digits | `ids must be positive integers` |

An empty list of results (`data: []`) is a normal `200`. `HEAD` answers like `GET` without a body.

```json example:by-ids-response
{
  "description": "Success",
  "data": [
    {
      "productId": 7,
      "productName": "Oud Royal",
      "productShortDescription": "Warm and woody",
      "productImageUrl": "https://img.example/oud.jpg",
      "categoryName": "Perfume",
      "price": "GHS 120.00",
      "originalPrice": "GHS 120.00",
      "onSale": false,
      "discountPercentage": null,
      "discountEndsAt": null,
      "isOutOfStock": false,
      "slug": "oud-royal-1a2b3c4d"
    }
  ]
}
```

## 2. `POST /api/v1/product/{slug}/view` (VIEW-01)

The product page is rendered on the frontend **server**, so the backend never sees the visitor's address on
`GET /product/{slug}`. A per-visitor count therefore has to be reported by the **browser**: the storefront product page
sends this beacon after it has rendered. `GET /product/{slug}` **no longer counts a view** (it had counted every
non-admin GET since before SEC-03c; reloads and loops inflated `view_count`).

| | |
|---|---|
| Auth | none (`permitAll` for POST on exactly this path). Cookies are optional: when the browser sends the session cookie of an ADMIN, the view is not counted (the owner browsing their own shop) |
| Request | no body, no query, no custom header. The storefront sends it as a CORS "simple" request (`fetch(..., {method: 'POST', keepalive: true, credentials: 'include'})` or `navigator.sendBeacon`), so no preflight is needed. The tenant comes from the `Origin` header |
| Success | **`204 No Content`**, empty body, `Cache-Control: no-store`. The answer is the same whether or not the view was counted (see "Uniform answer") |
| `429` | `Too many requests` (standard envelope) with `Retry-After`: the client address exceeded the rate limit. Clients ignore it (a lost view is not retried) |
| Other methods | The route is open for **POST only**. Any other method (`GET`, `HEAD`, `PUT`, `PATCH`, `DELETE`) from an **anonymous** caller is `401` (the same answer as any protected route; it never reaches the router), from a signed-in session `405`. None of them ever counts a view, and `HEAD` is **not** accepted. A preflight `OPTIONS` is answered by the CORS layer (see [cors.md](cors.md)) and counts nothing |
| Other | `403` for an `Origin` that is not an active tenant origin (CORS, before any handler runs) |

### What counts

A beacon increments `view_count` of the product (async, same counter and handler as before) only when **all** hold,
checked in this order:

1. **Rate limit** per client address (see below). Over the limit: `429`, nothing else is done.
2. **Not automated**: the request is not from a bot or a prefetcher. Not counted (still `204`) when the `User-Agent` is
   missing, longer than 512 characters, or matches a known crawler / HTTP-library / headless pattern
   (`bot`, `crawl`, `spider`, `slurp`, `headless`, `lighthouse`, `curl`, `wget`, `python-requests`, `okhttp`,
   `go-http-client`, `java/`, `libwww`, `facebookexternalhit`, `preview`, `scrapy`, `puppeteer`, `phantomjs`, ...), or when
   the request is a prefetch (`Purpose: prefetch`, `Sec-Purpose` containing `prefetch` or `prerender`, `X-Moz: prefetch`).
   The denylist is a case-insensitive substring match, so it has **known false positives**: any `User-Agent` containing
   `bot`, `monitor`, `preview`, `fetcher`, `scanner`, `archive` ... is treated as automated, for example the `CUBOT` Android
   phones ("...; CUBOT_X30 Build/...") match `bot`, so their visits are never counted. Accepted: the counter is analytics and
   the loss is a small, device-specific undercount; an allowlist for such devices can be added if one matters.
3. **The product exists, is active and listed in the caller's tenant.** Unknown, unlisted, inactive and malformed slugs
   (anything outside `[a-z0-9-]{1,200}`) are not counted. A malformed slug is rejected here, **after** step 1, so it spends a
   rate-limit token like every other beacon. The lookup uses the tenant-prefixed product cache.
4. **Not an ADMIN** session (see Auth).
5. **First view in the dedupe window**: at most one count per `(tenant, product, client key)` per **30 minutes**.

`HEAD` and `GET` never count. A product opened in several tabs, reloaded or revisited inside 30 minutes counts once per
client key.

**Wording.** The unit is the *client key and product per 30 minutes*, not "once per tab" or "once per page load": a second
tab, a reload, or a different browser behind the same address is the same visitor, and the same visitor counts again once the
window is over. Client documentation and copy must not promise a per-tab count. The window boundary is exact: a repeat
up to 29:59.999 after the counted view is deduped, from 30:00.000 on it counts again (a repeat never extends the window).

### Client key

`ClientIps.rateLimitKey`: the address the servlet container resolved behind our trusted proxies (never a raw
`X-Forwarded-For` / `Forwarded` value a client could choose), an IPv4 address as is and an IPv6 address reduced to its
**/64 prefix** (one subscriber owns a whole /64, so rotating the low 64 bits does not make a new visitor).

### Dedupe store

In memory, per backend instance (with N instances a visitor can count up to N times per window; accepted: the counter
is analytics, not accounting). Key = tenant slug + product id + client key, so **a visitor, or an attacker, can never
raise another tenant's counter and a tenant's keys never collide with another's**: the tenant comes from the
authenticated-by-origin request context, never from the URL or body, and the product id is looked up inside that
tenant's schema. The store is **bounded** (`catalog.views.dedupe-max-entries`, default 50000 keys) and evicts the oldest
entry when full; entries older than the window are dropped first. Memory is therefore at most about 50000 short keys no
matter how many addresses or slugs are tried; keys are only created for products that exist (step 3), after the rate
limit (step 1). The worst case of a full store is that the oldest visitors can be counted again, never unbounded growth.

### Rate limit

Token bucket per client key, **across tenants** (a single address cannot spread its allowance over many shops):
burst `catalog.views.rate-limit-burst` (default 30) refilling at that many per minute. At most 10000 client keys are
tracked; when that table is full a new key is served from one shared overflow bucket (fail closed: a flood of
addresses degrades to a global limit, it neither grows memory nor lifts the limit). Bots, rejected slugs and deduped
requests all spend tokens: every beacon does.

### Uniform answer

Counted, deduped, bot, unknown slug, wrong tenant and admin all answer the same `204`, so the endpoint is not an oracle
for "does this slug exist", "is this product unlisted" or "was I counted". Only the rate limit (`429`) and CORS (`403`)
differ.

### No PII

The client address is used only as an in-memory key (it is never written to the database, a log line or a metric
label). The handler logs the product id only (as before). The beacon path is **excluded from `SiteVisitFilter` for every HTTP
method** (POST, and also GET, PUT, DELETE, PATCH, OPTIONS, HEAD: only POST is routed, but a request with another method
used to be recorded with its address and slug), so no spelling of a request to it records the address and the (attacker
chosen) slug in `site_visits`.

### Slug length

The backend accepts a slug of **1 to 200** characters (`[a-z0-9-]`); a generated product slug is the slugified name plus 8
hex characters, so its length follows the product name (names are not length limited). The storefront currently skips the
beacon for a slug longer than **128** characters, so a product with a very long name is never counted from the browser even
though the backend would accept it. The two limits should be one number (the frontend raising its limit to 200, or product
names being capped); until then the longer slugs are an undercount, never an error.

### Residual risks

* **IPv6 rotation.** The client key is the /64 prefix, so rotating the low 64 bits gains nothing. An attacker (or one operator
  with a /48 or /32) can still use many different /64 prefixes: each is a separate visitor with its own rate-limit bucket and
  its own dedupe slot, so a single operator can add one view per /64, product and 30 minutes. Bounded by the same address
  space argument as the botnet case below.
* **Overflow bucket.** At most 10000 client keys are tracked. A flood from more than 10000 distinct keys (easy over IPv6)
  fills the table; every key that is not yet tracked then shares one overflow bucket, so genuine visitors arriving during the
  flood share that one bucket (30 per minute for all of them together) and are answered `429` beyond it: their views are lost,
  nothing else is affected and nothing grows. This is the deliberate fail closed choice. It ends soon after the flood: a
  bucket that spent one token is full again, and dropped from the table, after about 2 seconds.

* A botnet with many addresses can still add up to one view per address, product and 30 minutes; the rate limit bounds
  each address. The counter is advisory analytics (it feeds the "top viewed" reports), not money.
* Per-instance state: after a restart the dedupe store is empty (one more count per visitor and product at most).
* A client that never runs JavaScript (and any browser with the beacon blocked) is not counted. Intended.
