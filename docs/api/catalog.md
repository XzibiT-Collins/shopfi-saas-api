# Catalog: product create and family codes (ORD-02c)

Scope: `POST /api/v1/product/add-product` (ADMIN, `multipart/form-data`). Everything else about the product endpoints is
unchanged; see `documentation/complex-sku-stock-conversion.md` for the family / variant model.

## Family codes and SKUs

A new product (`isNewProduct=true`) creates a product family with a generated **family code** and its base unit
(`EA`) with SKU `<familyCode>-EA`. Variants added later get `<familyCode>-<uomCode>`.

Family code format: `<PREFIX>-<DIGITS>[-<SIZE>]`

| Part | Rule |
|---|---|
| `PREFIX` | 3 upper-case letters from the brand (or the name when there is no brand): the first letter of word 1 + two letters of word 2, or the first three letters of a single word |
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
(a different prefix). With the 4-digit fallback this needs more than ~11,000 families sharing one prefix and size, or
a near-simultaneous clash twice in a row.

Not changed: other constraint violations on create keep the generic `409 "A constraint violation occurred"` (no
`data.code`) and are not retried; adding a variant whose SKU `<familyCode>-<uomCode>` already exists keeps its
`400 "Variant with UOM <code> already exists for this family."`. A successful create still answers **200** (not 201)
with the product details (`stockKeepingUnit` = `<familyCode>-EA`).
