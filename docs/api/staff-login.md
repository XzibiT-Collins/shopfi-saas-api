# Staff sign-in: the front-desk login is the admin login (DOMAIN-FD)

Decision (2026-11-23): a `FRONT_DESK` user signs in exactly like an `ADMIN`. **The backend already worked this way; no
auth code changed.** This page documents the rule and the tests that pin it (`FrontDeskAdminLoginIntegrationTest`).

There is ONE tenant login for every role. The tenant comes from the browser `Origin` (custom domain or
`<slug>.<TENANT_BASE_DOMAIN>`), the role is read from the account, and the role decides what the session may call.

| Step | Endpoint | `ADMIN` | `FRONT_DESK` | `CUSTOMER` |
|---|---|---|---|---|
| Password sign-in | `POST /auth/login` | yes | yes | yes (storefront shoppers use the same endpoint) |
| E-mail code (when `LOGIN_OTP` is on for the role) | `POST /auth/login` -> `requiresOtp`, then `POST /auth/verify-login-otp`, `POST /auth/resend-login-otp` | per-role switch | per-role switch (`frontDeskEnabled`, [feature-flags.md](feature-flags.md)) | per-role switch |
| Who am I | `GET /auth/me` (also `data.auth` of the login answer) | `role: ADMIN`, `permissions: []` | `role: FRONT_DESK`, effective `permissions` | `role: CUSTOMER`, `permissions: []` |
| Forgot / reset | `POST /auth/forgot-password`, `POST /auth/reset-password` | yes | yes | yes |
| Own profile, change password, logout, refresh | `PATCH /auth/me`, `POST /auth/change-password`, `POST /auth/logout`, `POST /auth/refresh` | yes | yes | yes |

Details a shell needs ([auth-me.md](auth-me.md) has the field contract):

* The login answer carries the same `AuthResponse` as `/auth/me`: `role` and `permissions` are available from the first
  response; no second call is needed to decide which screens to show.
* **A `CUSTOMER` can authenticate here; that is not admin access.** Every `/admin/**` route answers `403` to a customer
  session. The admin shell must read `role` and show "not an admin account" for `CUSTOMER` itself.
* The account must be active and e-mail verified. Staff created in Settings -> Team are created verified. A deactivated
  account cannot sign in and its live session stops on the very next request.
* A wrong password, an unknown e-mail and an account of another shop all answer the same `401`.
* The reset link is e-mailed on the shop's own origin, built from `TENANT_BASE_DOMAIN` (or the verified custom domain),
  and a reset signs every older session out.
* No role can be chosen or changed through these endpoints. `PATCH /auth/me` edits the full name only; roles, the
  template and the permissions are `ADMIN` only (`/admin/front-desk/**`).

## Role boundaries are unchanged

Nothing under `/admin/**` was widened. A `FRONT_DESK` session reaches only the routes of [auth-me.md](auth-me.md)
(walk-in register, stock transfer and conversion, notifications, product search, storage locations and stock per
location), each behind its permission; everything else answers `403`.

### Candidates a front-desk screen may need (NOT changed, product decision)

| Route | Today | Why it may matter |
|---|---|---|
| `GET /admin/inventory/products/{id}/summary` | `ADMIN` only | the permission `PRODUCT_VIEW_STOCK_SUMMARY` exists but no route checks it; the per-location stock route is already open to front desk |
| `GET /product/admin/listing`, `GET /product/main/{id}` | `ADMIN` only | the permission `PRODUCT_VIEW_ADMIN_CATALOG` is likewise unchecked; front desk can use `GET /product/admin/search` |
| `GET /admin/product-families`, `.../{id}/available-uoms` | `ADMIN` only | a stock-conversion screen that wanted to list families would need it; the shipped frontend does not call it |

Validation note (existing behaviour, not changed): on a route with a required body, bean validation runs before the
role check, so an unauthorised caller that sends an INVALID body sees `400` instead of `403`; nothing is executed.
