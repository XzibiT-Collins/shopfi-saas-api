# Catalog: product API, family codes and delete (ORD-02c, API-03, CAT-05)

The first part documents the product endpoints (API-03: money as numbers, validation, delete, search, featured). The second
part, from "Family codes and SKUs", documents product create and family codes (ORD-02c). Responses use the envelope of
[errors.md](errors.md); see `documentation/complex-sku-stock-conversion.md` for the family / variant model.

## Product endpoints (`/api/v1/product`)

| Route | Auth | Notes |
|---|---|---|
| `POST /add-product` | ADMIN | `multipart/form-data` only |
| `PUT /update/{productId}` | ADMIN | `multipart/form-data` only; last write wins (no version check) |
| `DELETE /delete/{productId}` | ADMIN | see "Deleting a product" |
| `GET /main/{productId}` | ADMIN | `ProductDetails` |
| `PUT` / `DELETE /admin/{productId}/discount` | ADMIN | product discount |
| `GET /admin/listing`, `GET /admin/search` | ADMIN / ADMIN + FRONT_DESK | admin rows |
| `GET /featured`, `/listing`, `/search`, `/by-ids`, `/{slug}` | none | storefront, public rows |

### Request: `ProductRequest` (create and update, multipart form fields)

| Field | Rule |
|---|---|
| `productName` | required, not blank |
| `currency` | required, a supported currency code |
| `sellingPrice`, `costPrice` | required decimals, **`>= 0`** (a negative value is `400`) |
| `lowStockThreshold` | optional integer, **`>= 0`** |
| `conversionFactor` | optional integer, **`>= 1`** |
| `categoryId`, `familyId`, `uomCode`, `brand`, `size`, descriptions, `isNewProduct`, `isActive`, `isEnlisted`, `isFeatured` | optional, as before |
| `productImage` | optional file, see below |

A violated rule answers `400` with `description: "Validation failed"` and `data` = `{ "<field>": "<message>" }` for every
violated field. Prices are stored with 2 decimals (half-even rounding), as before.

**Content type.** `add-product` and `update` take `multipart/form-data` only. A JSON (or any other) body is **`415`**
`{"description": "Unsupported content type"}` (it used to be a `500`).

**Product image** (create and update). The type is decided from the file's magic bytes, never from its name or declared
content type: only **PNG, JPEG and WebP** are accepted, anything else (SVG, HTML, PDF, GIF, a renamed file) is
**`400`** `"Product image must be a PNG, JPEG or WebP image"`. The size cap is **5 MB**: larger is **`413`**
`"Product image must be at most 5 MB"` (the request as a whole is capped at 12 MB). Both are decided before anything is
uploaded or saved. If the image storage fails (network error, no URL returned) the answer is **`502`**
`data.code = IMAGE_UPLOAD_FAILED` `"Image storage is unavailable; nothing was saved"` and **nothing is written**; it used to
save the product with an empty image URL and answer `200`.

**What the magic-byte check does not prove (polyglots).** The check looks at the first bytes only (PNG signature, JPEG
`FF D8 FF`, WebP `RIFF....WEBP`) and does not decode the image. A file that starts with a valid JPEG header and carries
something else after it (HTML, script, an archive: a *polyglot*) is therefore accepted. This is a known, accepted limit,
not a bug: the file is never served from the API's own origin or as a document. It is handed to the image storage
(Cloudinary), which decodes and RE-PROCESSES every upload (re-encodes it as an image and serves it from its own domain
with an image content type), so trailing payload bytes do not survive. That storage re-processing is the mitigation; do
not add any code path that stores or serves the uploaded bytes unchanged. Branding uploads (`theme-contract.md` 7.2)
additionally read the pixel dimensions from the header and have the same limit and the same mitigation.

### Response: `ProductDetails`

Display strings are unchanged (`sellingPrice` "GHS 45.00" is the effective, discounted price; `originalPrice` the
pre-discount one; `costPrice` the cost). **Clients must not parse them**: the same values are now also given as numbers.

| Field | Type | Meaning |
|---|---|---|
| `sellingPriceAmount` | number | effective (discounted) selling price, 2 decimals |
| `originalPriceAmount` | number | pre-discount selling price (equal to `sellingPriceAmount` when not on sale) |
| `costPriceAmount` | number or `null` | cost price; `null` when the product has none (`costPrice` then reads `"0.00"`) |
| `currency` | string or `null` | currency code of the prices |
| `category` | object or `null` | `{categoryId, categoryName, description, slug}`; `slug` was always `null` here before API-03 |

All other fields (`productId`, `productName`, `stockQuantity`, `isActive`, `slug`, ...) are as before.

### Deleting a product

`DELETE /api/v1/product/delete/{productId}` answers `204`.

* A product created with `isNewProduct=true` is the **base unit of its own family**. It used to always answer `409` (the
  family points at its base unit and had no cascade). Now the family is deleted **with** its base unit when that product
  is the family's only member (one transaction).
* A base unit whose family still has other units (variants such as BOX): **`409`**, `data.code = FAMILY_HAS_VARIANTS`.
  Nothing is changed; delete the variants first, then the base unit.
* A variant (non-base unit) deletes on its own.
* A product that is referenced elsewhere (in a cart, an order, an inventory layer / movement, a stock transfer or a stock
  conversion) still answers the generic `409 "A constraint violation occurred"` and **nothing is deleted** (the family
  included). Deactivate such a product (`isActive=false`) instead.
* An unknown id is a no-op `204` (the delete is idempotent). The public catalog caches are cleared.
* Lock order (CAT-06 note): the hard delete locks the product row first, then hits the `cart_items` foreign key, while a
  checkout settlement deletes the cart lines first and then locks products. The two can wait on each other; the database
  resolves it by aborting one transaction and the settlement is retried, the delete answers its `409`/`503`. Harmless, so the
  order is documented rather than changed.

### Storefront search (CAT-05)

`GET /product/search?searchTerm=...` (and the admin `GET /product/admin/search`): the term is **literal text**. It is trimmed
(leading and trailing whitespace, a blank term means "no filter") and `%`, `_` and `\` in it are escaped, so `50%` finds
names containing the characters "50%" and a lone `%` or `_` matches only names that contain that character; they are no
longer wildcards. The match is a case-insensitive "contains" on the product name (the admin search also on the SKU).

### Featured shelf (CAT-05)

`GET /product/featured` returns up to 8 featured, active, listed products. `GET /product/featured?inStock=true` returns up
to **8 featured products that are in stock** (stock quantity above zero), so a shelf with sold-out products still fills up.
`inStock=false` (the default) is the unchanged list. Any other value of `inStock` is `400`. Both lists are cached
separately and evicted together with the rest of the public catalog.


## Family codes and SKUs

A new product (`isNewProduct=true`) creates a product family with a generated **family code** and its base unit
(`EA`) with SKU `<familyCode>-EA`. Variants added later get `<familyCode>-<uomCode>`.

Family code format: `<PREFIX>-<DIGITS>[-<SIZE>]`

| Part | Rule |
|---|---|
| `PREFIX` | **1 to 3 upper-cased characters** from the brand (or the name when there is no brand): the first character of word 1 + up to two characters of word 2, or up to the first three characters of a single word. Digits and punctuation are kept as they are (`7 Up` gives `7UP`, `A-B` gives `A-B`), and a short word gives a short prefix (`X` gives `X`), so do not assume three letters |
| `DIGITS` | random, zero-padded: **3 digits** (`000`-`999`), or **4 digits** (`0000`-`9999`) when the 3-digit space of that prefix is crowded (ORD-02c) |
| `SIZE` | the `size` field, upper-cased, whitespace removed; omitted when no size |

Examples: `VSP-042-50ML`, `VSP-0420-50ML`, `OUD-007`, `OUD-0007`. Treat the family code and the SKU as **opaque
strings**: do not parse the digits, and do not assume a fixed length. Codes issued before ORD-02c keep their 3 digits;
existing codes and SKUs are never changed.

Allocation (PO decision 2026-11-18):

1. Up to 10 random 3-digit codes are tried, then up to 10 random 4-digit codes. A code is used only when no family has
   it **and** no product already holds its base SKU `<code>-EA` (e.g. a legacy or customised SKU on another family).
2. If all 20 draws are taken: **409 `FAMILY_CODE_UNAVAILABLE`**, nothing is written.
3. Another create can take the same code between the check and the insert. That unique violation (on the family code
   or on the base SKU) is retried **once** in a fresh transaction with fresh draws; if it clashes again:
   **409 `FAMILY_CODE_UNAVAILABLE`**, nothing is written (no family without its product, no product without its family).

```json example:error-409
{
  "description": "Could not allocate a product code for this name/brand; try again or change the name",
  "data": { "code": "FAMILY_CODE_UNAVAILABLE" }
}
```

The `description` is fixed. The client may retry the same request (new random draws) or change the name / brand
(a different prefix). It becomes likely only for a crowded prefix and size: once the 3-digit space (1,000 codes) of one
prefix and size is full, a create is refused with probability `(n / 10,000)^10`, where `n` is the number of families
already sharing that prefix and size (about 0.01% at 4,000, 0.1% at 5,000, 2.8% at 7,000, 35% at 9,000; before the 3-digit
space is full it is practically zero), plus a near-simultaneous clash twice in a row.

Not changed: other constraint violations on create keep the generic `409 "A constraint violation occurred"` (no
`data.code`) and are not retried; adding a variant whose SKU `<familyCode>-<uomCode>` already exists keeps its
`400 "Variant with UOM <code> already exists for this family."`, which two identical concurrent adds also get (the loser's
insert hits the unique SKU; ORD-02d: it used to be the generic 409). A successful create still answers **200** (not 201)
with the product details (`stockKeepingUnit` = `<familyCode>-EA`).

Known limits (ORD-02d, not changed): (1) the image is uploaded before the rows are written, so a create that then fails
(`FAMILY_CODE_UNAVAILABLE`, a constraint violation) leaves that uploaded image in the image storage without a product
pointing at it (there is no clean-up of storage assets yet); (2) a legacy product whose SKU already is `<familyCode>-BOX`
but belongs to no family makes adding a BOX variant to the family with that code fail with the `400 "Variant with UOM BOX
already exists for this family."` although the family has no BOX unit: rename the legacy SKU.
