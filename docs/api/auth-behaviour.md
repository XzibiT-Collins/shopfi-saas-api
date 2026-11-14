# Tenant auth behaviour (AUTH-01)

Contract notes for the storefront (UI-05) and admin clients. All endpoints are under `/api/v1/auth` on the single API
host; the tenant is resolved from the browser `Origin` (custom domain or `<slug>.<base-domain>`). Auth cookies
(`accessToken`, `refreshToken`) are HttpOnly, host-only on the API origin and therefore **shared by every tenant**: a
browser that visited shop A sends A's cookies when it talks to shop B.

## 1. Cookies that cannot be used are ignored, not rejected

A cookie that is expired, malformed, signed with another key, issued for another tenant, issued for the platform, or
has no tenant claim is treated as **anonymous**. It never produces a plain-text 401 any more.

| Path kind | Result with an unusable access cookie |
|---|---|
| Public / `permitAll`: `/auth/login`, `/auth/logout`, `/auth/refresh`, `/auth/register`, `/auth/verify-otp`, `/auth/resend-otp`, `/auth/forgot-password`, `/auth/reset-password`, catalog, `/oauth2/**` | Proceeds as anonymous (normal success or the endpoint's own error) |
| Protected (`/auth/me`, account, admin APIs) | `401` with the usual JSON envelope `{"description": "...", "data": null}` (`Content-Type: application/json`). The text never mentions tenants or mismatches |

Consequences for clients:

* Login, logout, register, OTP, forgot/reset work on a second shop while the first shop's cookies are still present.
  A successful login on shop B overwrites the cookies with B's (one session per browser per API host, unchanged).
* `POST /auth/logout` always returns `204` and always clears both cookies (`Max-Age=0`), even when the access token
  is unusable or absent.
* The server does not clear a foreign cookie it ignores (that would sign the user out of the other shop).
* The same applies to public data endpoints such as `GET /api/v1/theme`: another tenant's cookie yields the normal anonymous `200` for the requesting tenant only (never a `401`, never the other tenant's data), and a write with that cookie is `401`.

## 2. `/auth/refresh`

* Works when the **access** cookie is expired, invalid or missing, as long as the **refresh** cookie is valid for the
  resolved tenant. Response `200`, new `accessToken` cookie.
* No refresh cookie (or empty), an expired/invalid one, or one issued for another tenant or the platform:
  **`401`** JSON `{"description":"Invalid or expired token"}` (was `400` for a missing cookie, and a message that
  named the tenant mismatch for a foreign one). No cookie is set.
* The client rule stays: on a protected `401`, call refresh once, then retry once, then go to login.

## 3. Cookie lifetimes

`Max-Age` of `accessToken` / `refreshToken` is now derived from `JWT_ACCESS_EXPIRATION` / `JWT_REFRESH_EXPIRATION`
(milliseconds, rounded up to whole seconds), so the cookie and the token expire together. Previously they were
hard-coded to 3600 and 604800 seconds regardless of the env values.

## 4. Password-reset links are tenant-aware

The emailed link is built from the tenant the request was made on:

1. tenant custom domain: `https://<custom-domain><frontend.reset-password-path>?resetPasswordToken=<token>`
2. otherwise `<onboarding.public-scheme>://<slug>.<tenant.base-domain><frontend.reset-password-path>?resetPasswordToken=<token>`
3. dev only (no custom domain and blank `tenant.base-domain`): `FRONTEND_CHANGE_PASSWORD_URL` is the fallback origin;
   `{slug}` in it is replaced and `&tenant=<slug>` is appended.

`frontend.reset-password-path` defaults to `/reset-password`. In production `FRONTEND_CHANGE_PASSWORD_URL` is still a
required env var but is only used by rule 3. The frontend contract (`resetPasswordToken` query parameter,
`POST /auth/reset-password?resetPasswordToken=...`) is unchanged.

Reset tokens, registration OTPs, login challenges and login OTPs are stored under tenant-scoped keys
(`otp:<kind>:<slug>:...`). A token or OTP issued on one tenant is invalid on every other tenant, and the same email
registered on two tenants has independent codes. Tokens issued before this deploy (15 minute TTL) stop working.

### OAuth2 failure redirect

A failed Google login redirects to the tenant (the validated `redirect_uri` of that login, else the tenant's login
page `https://<tenant host>/login`, configurable `application.oauth2.failure-path`) with a **fixed**
`error=oauth2_failed`. The provider/exception text is never put in the URL. Only if the tenant cannot be identified
(missing state cookie) does it use the global `OAUTH2_REDIRECT_URIS`, still with the fixed code.

## 5. `POST /auth/resend-otp`

* Same `200 {"description":"OTP resent successfully"}` for an unknown email, an already verified email and a pending
  one. Only a pending (unverified) account is actually emailed. (Before: `401 User not found` for unknown.)
* Throttled with `429` + `Retry-After` (seconds): per client IP (default 10 per 10 min) and per tenant+email (default
  3 per 10 min); the checks run before any account lookup so they are identical for known and unknown emails.
  Properties: `application.auth.rate-limit.resend-otp-{ip,email}.{capacity,period-seconds}`. State is per JVM instance.
* The body is now validated: a missing/invalid email format is `400`.
* OTP codes come from `SecureRandom`. Verification is capped at 5 wrong guesses per issued code (registration and
  login OTP): on the 5th wrong guess the code is destroyed and the user must request a new one (verify keeps answering
  `400 Invalid or expired OTP`; for login OTP `Invalid or expired login challenge`). A new code resets the budget.

## 6. Passwords

Registration and reset share one server-side policy, evaluated on the **stripped** password (login strips too):
at least 8 characters (UTF-16 units), at most 72 UTF-8 bytes, one `A-Z`, one `0-9`, one special from
`` !@#$%^&*()_+-=[]{};':"\|,.<>/? ``.

* `POST /auth/register`: weak `password` now `400` (`Validation failed` with a field message). `password` and
  `confirmPassword` are compared after stripping and the stripped value is hashed.
* `POST /auth/reset-password`: same policy (adds the length rules the old regex lacked), compared and hashed after
  stripping, so a password with edge whitespace set via reset can log in. An invalid body is `400` (it used to be
  `500` because Spring's method validation exception was not handled).
* Messages: `Password must contain at least one uppercase letter, one number, and one special character.`,
  `Password must be at least 8 characters long.`, `Password must be at most 72 bytes long.`

## 7. Delivery details

`POST /delivery-detail/add-address`: `label` is optional and defaults to `HOME` (was a DB `NOT NULL` failure when
omitted); `landmark` is optional and stored as `null` (was `@NotNull`, 400). `PUT .../update/{id}` is unchanged
(omitted fields are left as they are).

## SEC-03: deny by default, 401 vs 403

`SecurityConfig` now permits anonymous access only through an explicit, method-specific allow-list (see
[authorization-matrix.md](authorization-matrix.md)). Consequences for clients:

* Category and product **mutations and admin reads** (`POST/PUT/DELETE /category/**`, `GET /category/{id}`,
  `POST/PUT/DELETE /product/**`, `GET /product/admin/listing`, `GET /product/main/{id}`) answer **401** to an anonymous
  caller, a foreign-tenant token and a platform token (it used to be **403**, produced by `@PreAuthorize` after a
  permitAll matcher). A signed-in non-admin still gets **403**. `GET /product/admin/search` also allows `FRONT_DESK`.
* `GET /category/all`, `GET /product/{featured,listing,search,<slug>}` and `GET /theme` stay public.
* Unlisted methods/paths under former `permitAll` prefixes (`/auth/**`, `/webhook/**`, `/platform/auth|onboarding/**`,
  swagger) are now authenticated-only (401 anonymous) instead of falling through to a 404/405.
* `/api/v1/admin/accounting/**` requires `ADMIN` (it previously only required a login).
* `/api/v1/dev/**` is `denyAll` (401/403) unless `tenant.dev-endpoints.enabled=true`.
