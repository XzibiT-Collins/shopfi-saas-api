# GET /api/v1/auth/me for staff: role and effective permissions (API-RD-06, backend part)

Question from the board: does the backend already expose what a front-desk UI shell needs, the **role** and the
**effective permissions** of the signed-in account? **Yes, for both `ADMIN` and `FRONT_DESK`. No field was added, no code
changed**; this page documents the contract and pins it with tests. Which screens a front-desk login gets is a product
decision and is **not** made here (see "Options for the product decision").

## Contract

`GET /api/v1/auth/me`, any signed-in tenant user (`ADMIN`, `FRONT_DESK`, `CUSTOMER`). Anonymous, a foreign-tenant token and
a platform token get `401`; a deactivated account's token is refused (`401`). The same object is returned as `data.auth` by
`POST /auth/login` (and `verify-login-otp`), so a shell can build itself from the login response and refresh from `/me`.

```json auth-me-front-desk
{
  "description": "Authenticated user fetched successfully",
  "data": {
    "id": 17, "email": "desk@shop.example", "fullName": "Ama Desk", "profilePicture": "",
    "role": "FRONT_DESK",
    "permissions": ["CUSTOMER_SEARCH", "WALK_IN_ORDER_CREATE", "WALK_IN_ORDER_VIEW"]
  }
}
```

```json auth-me-admin
{
  "description": "Authenticated user fetched successfully",
  "data": {
    "id": 1, "email": "owner@shop.example", "fullName": "Shop Owner", "profilePicture": "https://cdn.example.com/p.png",
    "role": "ADMIN",
    "permissions": []
  }
}
```

| Field | Meaning |
|---|---|
| `role` | `ADMIN`, `FRONT_DESK` or `CUSTOMER` |
| `permissions` | the **effective** front-desk permissions (`FrontDeskPermission`, order not significant): the tenant's default template (enabled entries) plus the account's allowed overrides, minus its denied overrides: exactly what `GET /admin/front-desk/users/{id}/permissions` shows as `effectivePermissions` ([team-staff.md](team-staff.md)) |
| `profilePicture` | image URL, `""` (never `null`) when none |

Rules a client must follow:

1. **`ADMIN` has `permissions: []` and that does NOT mean "no access".** An `ADMIN` passes every permission check by role.
   The shell must treat `role === "ADMIN"` as "everything", and use `permissions` only for `FRONT_DESK`.
2. **`CUSTOMER` also gets `[]`.** Only `FRONT_DESK` ever carries a non-empty set.
3. **A new shop's template is empty.** Nothing is seeded: a `FRONT_DESK` account created before an admin saved the default
   template (or gave overrides) has `permissions: []` and can use nothing but the role-only routes below. An empty set is a
   normal state: show "ask the owner for access", not an error.
4. **Permissions are read from the database on every call, not stored in the token.** An admin's change in Settings -> Team
   is visible on the user's very next `/me` (and enforced on the very next request); the session need not be re-created.
   A shell that cached the set should refetch `/me` on focus or after a `403`.
5. The set is per tenant: the same e-mail in another shop has its own role and permissions.

Related, unchanged by this page: `PATCH /auth/me` (own full name only) and `POST /auth/change-password` work for any signed-in
role including `FRONT_DESK` and answer the same `AuthResponse`; a password change signs the account's other sessions out
([auth-behaviour.md](auth-behaviour.md), section 8, API-RD-08).

## What a `FRONT_DESK` session can reach (derived from the code)

Two layers: the **role** gate (`hasAnyRole('ADMIN','FRONT_DESK')`, a `403` for everyone else) and, on some routes, a
**permission** gate inside the service (`403` for a front-desk account without it: `You do not have permission to perform this
walk-in action.` on the walk-in routes, `You do not have permission to perform this action.` on stock transfer and conversion;
`ADMIN` always passes).

| Permission | Routes it gates | Notes |
|---|---|---|
| `WALK_IN_ORDER_CREATE` | `POST /admin/walk-in/quote`, `POST /admin/walk-in/order` | cancel and refund are `ADMIN` only |
| `CUSTOMER_SEARCH` | `GET /admin/walk-in/customers/search`, `POST /admin/walk-in/customers/search` | |
| `WALK_IN_ORDER_VIEW` | `GET /admin/walk-in/orders`, `GET /admin/walk-in/orders/{orderNumber}` | |
| `WALK_IN_ORDER_MARK_RECEIPT_PRINTED` | `PATCH /admin/walk-in/orders/{orderNumber}/receipt-printed` | |
| `STOCK_TRANSFER` | `POST /admin/inventory/transfers`, `GET /admin/inventory/transfers` | |
| `STOCK_CONVERSION` | `POST /admin/stock-conversions/forward`, `POST /admin/stock-conversions/reverse`, `GET /admin/stock-conversions/reverse/{id}/variants` | |
| `PRODUCT_VIEW_ADMIN_CATALOG` | **nothing today** | the name exists and can be granted, but no code checks it. `GET /product/admin/search` is open to `FRONT_DESK` by role alone |
| `PRODUCT_VIEW_STOCK_SUMMARY` | **nothing today** | same: `GET /admin/inventory/products/{id}/stock-by-location` and `GET /admin/inventory/locations[/{id}]` are role-gated only |

Role-only routes (no permission needed): `GET /admin/notifications` and the other three notification routes
([notifications.md](notifications.md)), `GET /product/admin/search`, `GET /admin/inventory/locations`, `GET
/admin/inventory/locations/{id}`, `GET /admin/inventory/products/{id}/stock-by-location`. Everything else under `/admin/**`
(orders, metrics, accounting, tax, coupons, team, settings, product admin listing and writes) is `ADMIN` only and answers
`403` to `FRONT_DESK`.

The two `PRODUCT_VIEW_*` permissions being decorative is a **finding, not a change**: enforcing them now would lock out
front-desk accounts that were created while they could reach those routes; the product owner decides (open item in the PR).

## Missing fields? Assessed, none needed

A shell needs to know who is signed in, what they may do and where to land. `role` + `permissions` + `fullName` +
`profilePicture` cover that; the frontend's `schema.ts` already types `permissions: string[]`. The permission to screen map
is the table above (static, so it belongs in the frontend). The shop's name and theme come from `GET /theme`. Not added on
purpose: a precomputed `isAdmin`/`allPermissions` flag (it only repeats `role`), the template, or the overrides (admin-only
data of `GET /admin/front-desk/**`).

## Options for the product decision (not decided here)

The login endpoint is the same for every role (`POST /auth/login`, cookies, tenant from the Origin); nothing on the backend
needs to change for any option.

* **A. Status quo, `ADMIN` only.** Staff added in Settings -> Team cannot sign in to the web UI (the frontend `AdminGate`
  admits `ADMIN`); the walk-in register is used from an admin session. No work, but the Team page creates accounts that have
  no UI.
* **B. Widen the existing admin shell by role and permission.** Same login page; `AdminGate` admits `FRONT_DESK`; the sidebar
  shows only the entries whose permission is in `permissions` (walk-in: the three `WALK_IN_*` plus `CUSTOMER_SEARCH`; stock:
  `STOCK_TRANSFER`, `STOCK_CONVERSION`), plus the notifications bell. Least new UI; the risk is a forgotten `ADMIN`-only
  screen reachable by URL, so every admin page needs a role guard (the server already answers `403`).
* **C. A separate front-desk entry (own route group and a small shell), same endpoint.** Smaller surface and a till-friendly
  layout, one more app area to maintain; the same permission map applies.
* **Either B or C, plus:** `LOGIN_OTP` can be switched per role (`frontDeskEnabled`, [feature-flags.md](feature-flags.md)), so
  front-desk logins can require an e-mail code independently of admins; a shared till account would make that awkward.
  Deactivating a staff member (`PATCH /admin/front-desk/users/{id}`, `isActive: false`) stops their token at once.
