# How a product sale and the store-wide sale combine (API-RD-09, discount part)

Derived from the code (`EffectivePriceService`, `CheckoutPricingService`, `WalkInOrderServiceImpl`), not from intent, and
pinned by `DiscountStackingDocContractTest`. Older prose: `DISCOUNTS_FRONTEND_INTEGRATION.md` (same rules, less precise).

## The rule: one automatic discount per product, the product's own wins, nothing stacks

For every product the server picks **at most one** automatic discount, in this order:

1. **The product's own discount**, if it is in force now (`discountType`, `discountValue`, `discountStartAt <= now <=
   discountEndAt`, both ends inclusive). It is `PERCENTAGE` or `FLAT`.
2. else **the store-wide discount**, if one is in force now. It is a **percentage only** (`discountPercentage`, 0 < p <= 100)
   and applies to every product that has no product discount in force.
3. else no discount (`source: NONE`).

It is **not** "the larger of the two" and **not** the two combined: a product on a 10% sale during a 50% store-wide sale
sells at 10% off, and a product with a FLAT 5 sale during a 50% store-wide sale sells at 5 off. The product's own sale
wins even when the store-wide one is better for the customer. A product whose own window has ended or not begun is
treated as having none, so it falls through to the store-wide discount.

The discount is capped by the price (a FLAT 500 on a 100 product gives price 0, never negative). Amounts are rounded to 2
decimals (percent discounts `HALF_UP`, the resulting price `HALF_EVEN`).

Worked table, **every row is evaluated against the real pricing code by the contract test** (`price` is the base price;
`product` is a product discount in force unless it says otherwise; `shop` is the store-wide percentage in force):

```json pricing-cases
[
  { "case": "no discount",                         "price": "100.00", "product": null,                                   "shop": null, "effective": "100.00", "onSale": false, "source": "NONE" },
  { "case": "store-wide only",                     "price": "100.00", "product": null,                                   "shop": "20",  "effective": "80.00",  "onSale": true,  "source": "SHOP" },
  { "case": "product percentage only",             "price": "100.00", "product": { "type": "PERCENTAGE", "value": "10" }, "shop": null, "effective": "90.00",  "onSale": true,  "source": "PRODUCT" },
  { "case": "product flat only",                   "price": "100.00", "product": { "type": "FLAT", "value": "5" },        "shop": null, "effective": "95.00",  "onSale": true,  "source": "PRODUCT" },
  { "case": "both: product wins, no stacking",     "price": "100.00", "product": { "type": "PERCENTAGE", "value": "10" }, "shop": "20",  "effective": "90.00",  "onSale": true,  "source": "PRODUCT" },
  { "case": "both: smaller product sale still wins", "price": "100.00", "product": { "type": "FLAT", "value": "5" },      "shop": "50",  "effective": "95.00",  "onSale": true,  "source": "PRODUCT" },
  { "case": "product window ended: store-wide",    "price": "100.00", "product": { "type": "PERCENTAGE", "value": "10", "window": "ENDED" }, "shop": "20", "effective": "80.00", "onSale": true, "source": "SHOP" },
  { "case": "product window not started: store-wide", "price": "100.00", "product": { "type": "PERCENTAGE", "value": "10", "window": "FUTURE" }, "shop": "20", "effective": "80.00", "onSale": true, "source": "SHOP" },
  { "case": "flat larger than price clamps to 0",  "price": "100.00", "product": { "type": "FLAT", "value": "500" },      "shop": "20",  "effective": "0.00",   "onSale": true,  "source": "PRODUCT" },
  { "case": "product sale that rounds to nothing still blocks the store-wide one", "price": "1.00", "product": { "type": "PERCENTAGE", "value": "0.04" }, "shop": "20", "effective": "1.00", "onSale": false, "source": "NONE" }
]
```

The last row is an edge case, documented and **not changed**: a product discount in force that works out to a zero
reduction at 2 decimals still counts as "the product's own discount", so the store-wide one is not applied to that product.

Where the result shows up: `price` / `originalPrice` / `onSale` / `discountPercentage` / `discountEndsAt` in the catalog
and cart rows ([catalog.md](catalog.md)). The **source** (`PRODUCT` / `SHOP`) is not sent to clients, so a UI cannot label
a price "store-wide"; it can only show `onSale`, the percentage and the end date. Checkout, cart preview and walk-in use the
same code, so display and charge cannot differ.

## Coupons and manual discounts never stack with an automatic discount

* **Checkout and cart preview.** If **any** line of the cart is on sale (`onSale`, from either source), a coupon is refused:
  checkout `400` with `Coupons cannot be combined with active product discounts.`; the preview answers `coupon.valid: false`,
  `reason: COUPON_NOT_COMBINABLE` with the same message. The refusal is the same for a store-wide sale: **while a
  store-wide discount is in force every priced product is on sale, so no coupon can be applied** (the UI should hide or
  disable the coupon field then, not only for product sales). The check runs before the coupon is looked up, so it never says
  whether the code exists.
* **Walk-in quote and sale.** If any line is on sale, a manual order discount (`discountType` or `discountValue` present) is
  refused: `400` `Manual discount cannot be combined with active product discounts.` (the text says "product" but also
  covers the store-wide sale). With no line on sale the manual discount applies to the subtotal as usual
  ([walk-in.md](walk-in.md)).
* `GET /tax?subtotal=&couponCode=` is only a calculator over a number the caller sends ([tax.md](tax.md)); it does not apply
  this rule. Do not use it to decide whether a cart may use a coupon.
* Without an automatic discount a coupon (checkout) or manual discount (walk-in) works as before. Tax is always computed on
  the amount after all discounts.

## Setting the discounts (for completeness)

Product discount: `PUT /product/admin/{productId}/discount` (type, value > 0, start, end), removed with `DELETE`; store-wide:
`PUT /admin/shop-discount` (percentage 0 < p <= 100, start, end), one at a time, a new one retires the old one at once;
details and `status` values in [checkout.md](checkout.md#shop-wide-discount-admin-reads-api-06).

## Product decision, confirmed 2026-11-23 (no code change)

Both rules are the product owner's decision and are today's behaviour: **a coupon is not allowed when any item in the cart is on
discount** (a product discount or the store-wide discount; checked on every line, before the coupon is looked up), and **the
product's own discount wins over the store-wide one**. Pinned by `DiscountStackingDocContractTest`
(`couponIsRefusedWhenAnyLineIsOnSaleFromEitherSource`, the `pricing-cases` table and `decisionsOfTheProductOwnerArePinned`).
Changing either is a one-place change in `EffectivePriceService` / `CheckoutPricingService` and must update the test and this
page together.
