# Tax API (API-RD-11a, 11c; the tax part of API-RD-02)

Base path `/api/v1/tax`, tenant scoped. Roles: `GET /` (tax calculation) and `GET /all` (rate list) are readable by any
signed-in tenant user; `add-tax`, `update/{id}`, `{id}` and `delete/{id}` are `ADMIN` only (anonymous `401`, others `403`;
[authorization-matrix.md](authorization-matrix.md)). Bodies are `{ "description": string|null, "data": ... }`.

| Method | Path | Purpose |
|---|---|---|
| GET | `/?subtotal=&couponCode=` | taxes and totals for a subtotal (checkout calculation, unchanged) |
| GET | `/all` | every tax; each carries `version` |
| GET | `/{taxId}` | one tax; `ETag` header + `data.version` |
| POST | `/add-tax` | create; response carries `ETag` / `version` |
| PUT | `/update/{taxId}` | replace name / code / rate / active / compound / order; optional `If-Match` |
| DELETE | `/delete/{taxId}` | delete (`204`) |

## Error codes (API-RD-11a)

`description` is unchanged word for word; branch on `data.code`.

| Status | `data.code` | `description` (exact) | When |
|---|---|---|---|
| 409 | `TAX_NAME_EXISTS` | `Tax already exists with name: <name>` | create, or update to another tax's name |
| 409 | `TAX_CODE_EXISTS` | `Tax already exists with code: <code>` | create, or update to another tax's code |
| 404 | `TAX_NOT_FOUND` | `Tax not found with id: <id>` | get / update / delete of an unknown id |
| 409 | `TAX_VERSION_CONFLICT` | `This tax was changed by someone else. Reload and try again.` | stale `If-Match` on update (`data.currentVersion` = token to reload against) |
| 400 | `INVALID_IF_MATCH` | `If-Match must be the ETag returned by the last read of this resource.` | malformed `If-Match` |

Not coded on purpose: bean-validation refusals (`Tax name is required`, negative rate, ... keep `"Validation failed"` +
`data.<field>`), and the coupon refusals of `GET /?couponCode=` (`Invalid coupon code.` and the coupon reasons): the
coupon contract keeps its reasons uncoded so that the endpoint cannot be used to learn which codes exist (API-06).

## Optimistic concurrency on tax saves (API-RD-11c)

Same contract as the team saves ([team-staff.md](team-staff.md#optimistic-concurrency-api-rd-11c)): the tax's `version`
is a SHA-256 prefix of its saved state (id, name, code, rate at scale 2, active, compound, apply order), also sent as
`ETag`. `PUT /update/{taxId}` with `If-Match: "<version>"` takes a row lock on the tax, compares under the lock and
writes only on a match; of N saves with one token exactly one wins, the rest are `409 TAX_VERSION_CONFLICT` and write
nothing. This covers the active toggle (an `isActive` change is an update). **No header (or `*`): last write wins, as
before; deprecated.** `version` is additive in `TaxResponse`, so existing readers are unaffected.

```json tax-response
{
  "description": null,
  "data": {
    "id": 3, "name": "VAT", "code": "VAT", "rate": 15.00, "isActive": true, "isCompound": false, "applyOrder": 1,
    "createdAt": "2026-11-01T09:00:00", "updatedAt": "2026-11-20T14:30:00",
    "version": "0f1e2d3c4b5a69788796a5b4c3d2e1f0"
  }
}
```

## GET /?subtotal=&couponCode= (calculation)

Any signed-in tenant user. `subtotal` is required (a number; missing or not a number is `400`). Returns the active taxes
(ascending `applyOrder`) charged on `subtotal`, after the coupon discount when `couponCode` is given. `discount` is the
coupon amount taken off (`0` without a coupon). It is a **calculator over the number you send**: it does not know the
caller's cart, so it does **not** apply the "no coupon on a sale item" rule ([discount-stacking.md](discount-stacking.md));
the cart preview and checkout are the authority for a real cart.

```json tax-calculation-response
{
  "description": null,
  "data": {
    "orderTaxes": [
      { "id": null, "taxName": "VAT", "taxAmount": 15.00, "taxRate": 15.00, "taxCode": "VAT", "currency": "GHS",
        "taxAmountFormatted": "GHS 15.00", "taxRatePercent": 15.00 }
    ],
    "totalTaxAmount": "GHS 15.00", "totalAmountAfterTax": "GHS 115.00", "discount": 0
  }
}
```

## Request body (API-RD-02)

`POST /add-tax` and `PUT /update/{taxId}` take the same body (JSON). `rate` is a percentage (`15.00` = 15%, not 0.15) and may
be `0`; it must not be negative. Missing `name` / `code` / `rate` / `applyOrder` and a negative `rate` are `400`
`"Validation failed"` with `data.<field>` (`Tax name is required`, `Tax code is required`, `Tax rate is required`,
`Tax rate cannot be negative`, `Apply order is required`). `isActive` and `isCompound` are optional booleans.
`applyOrder` decides the order taxes are applied in; a compound tax is charged on the amount plus the earlier taxes. Responses
are `tax-response` above (`POST` carries the new `ETag`); `DELETE` answers `204` with no body.

```json tax-request
{ "name": "VAT", "code": "VAT", "rate": 15.00, "isActive": true, "isCompound": false, "applyOrder": 1 }
```

## Frontend follow-up

`src/lib/admin/taxes/errors.ts` (matches the duplicate and not-found descriptions) can switch to `data.code`;
`TaxesTab.tsx` / `TaxModal.tsx` should send the row's `version` (from `GET /all`) as `If-Match` on the active toggle and
edit, and handle `TAX_VERSION_CONFLICT` by reloading the list.
