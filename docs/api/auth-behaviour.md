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
* Token types (FIX-A2): only a token typed `refresh` is accepted by `/auth/refresh`; an ACCESS token sent as the
  `refreshToken` cookie is `401` (it could otherwise be exchanged for new access tokens indefinitely). Conversely only a
  token typed `access` authenticates a REST request: a refresh token sent as the `accessToken` cookie leaves the request
  anonymous (`401` on protected paths). Tokens minted before the `typ` claim existed have no type: they are refused
  by both, except that `/auth/refresh` accepts an UNTYPED refresh token while `JWT_REFRESH_UNTYPED_GRACE_UNTIL` (ISO-8601
  instant, e.g. deploy time plus `JWT_REFRESH_EXPIRATION`) is in the future, so existing sessions survive the rollout.
  Blank (default) or malformed = no grace. Remove the variable once the instant has passed.
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
* Throttled with `429` + `Retry-After` (seconds): per client IP (default 10 per 10 min), per client IP + tenant + email
  (default 3 per 10 min; AUTH-02: a stranger cannot spend a victim's budget from another address) and a looser per
  tenant+email ceiling (default 10 per 10 min) bounding mail-bombing from many addresses; the checks run before any
  account lookup so they are identical for known and unknown emails.
  Properties: `application.auth.rate-limit.resend-otp-{ip,email}.{capacity,period-seconds}`,
  `application.auth.rate-limit.resend-otp-email-ceiling.capacity`. State is per JVM instance.
* AUTH-02 AC3: `POST /auth/forgot-password` is throttled the same way (`forgot-password-ip.capacity` 10,
  `forgot-password-email.capacity` 3, shared ceiling) and `POST /auth/resend-login-otp` per client IP only (its body is
  a challenge token, no email; `resend-login-otp-ip.capacity` 10). 429 + `Retry-After`, before any lookup.
* AUTH-02 AC2/AC4: an OAuth2 `redirect_uri` is trusted only if it is `https` (plain `http` only in dev), carries no
  user-info and uses the default port (any port in dev), in addition to the host rule. After a successful login with
  no valid stashed redirect the browser goes to the tenant's own origin at the configured fallback's path (the global
  `OAUTH2_REDIRECT_URIS` only when no tenant is known).
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

## 8. Customer self-service: `PATCH /auth/me` and `POST /auth/change-password` (API-RD-08)

Both are for the **signed-in caller's own account** and work for any tenant role (`CUSTOMER`, `ADMIN`, `FRONT_DESK`). The
target is always the authenticated principal; there is no id in the path or body, and extra body properties (`role`, `email`,
`isActive`, `password`, `id`, ...) are ignored. Anonymous, platform and other-tenant credentials, and a refresh token in the
`accessToken` cookie, get `401`. The account is looked up in the caller's own tenant schema, so another tenant's user with the
same e-mail is never touched.

### `PATCH /api/v1/auth/me`

```json example:update-profile-request
{ "fullName": "Ama Mensah" }
```

`fullName` is trimmed, 1 to 100 characters, no control characters (`400` otherwise, also when the field is missing). The
response is the same `AuthResponse` as `GET /auth/me` (message `Profile updated successfully`). It does **not** touch the
session: no new cookies, no revocation (re-minting a longer-lived refresh token from an access token alone would defeat the
short access lifetime). The `fullName` claim inside tokens issued earlier is only informational; the server always reads the
name from the database.

### `POST /api/v1/auth/change-password`

```json example:change-password-request
{ "currentPassword": "Cust0mer!pass", "newPassword": "N3w-Passw0rd!", "confirmPassword": "N3w-Passw0rd!" }
```

* `currentPassword` is required and verified first (compared stripped, like login). A wrong one is
  `400` `{"description": "Current password is incorrect", "data": {"code": "CURRENT_PASSWORD_INCORRECT"}}` and changes
  nothing. (The password policy of `newPassword` is public and is enforced by request validation first, so a weak new
  password is a `400` with the policy text before the current password is looked at.) (`400`, not `401`, so the client does not treat it as an expired session and sign the user out.)
* `newPassword` follows the policy of sign-up and reset (section 6; the stripped value is validated, compared and stored).
  A weak value is `400` with the policy message. Reuse is `400` `New password cannot be same as current password`, also when
  only surrounding whitespace differs. `confirmPassword` is optional; when sent it must equal `newPassword` (`400 Passwords do not match`).
* An account without a local password (Google sign-in) gets `400` with `data.code` `NO_LOCAL_PASSWORD`: use "Forgot password"
  to set one.
* **Revocation.** Each tenant user has a `token_version` (migration V50). Every access and refresh token carries it in the `tv`
  claim (a token without the claim counts as version 0). A successful change bumps the version in the same transaction as the
  new hash, so **every token minted before the change stops working on every device**: `401` on any protected route and on
  `POST /auth/refresh`, and `STOMP CONNECT` with it is refused (a socket that is already open ends with its access token's
  expiry). The response `Set-Cookie` re-issues `accessToken` and `refreshToken` at the new version, so the calling browser stays
  signed in. Sign-in with the old password fails; with the new one it works.
  Password **reset** (`/auth/reset-password`) does not bump the version yet (open item, same effect available by calling this endpoint).
* **Concurrency.** The user row is locked, so two simultaneous changes serialise: exactly one wins (`200`) and the other is
  checked against the new hash (`400 CURRENT_PASSWORD_INCORRECT`); the version goes up by one.
* **Rate limit** (the endpoint guesses the current password with a session that may be stolen): every attempt, success or
  failure, consumes a token from two buckets: per client IP (`application.auth.rate-limit.change-password-ip.capacity`, default
  **20** per 600 s) and per tenant + user (`...change-password-user.capacity`, default **5** per 600 s). Over the limit:
  `429`, `Retry-After`, and the right password is refused too while limited.

| Status | When |
|---|---|
| 200 | changed (cookies re-issued) |
| 400 | validation, wrong current password, policy, reuse, mismatch, `NO_LOCAL_PASSWORD` |
| 401 | no / invalid / revoked / foreign credentials |
| 429 | rate limited |

Frontend (account page): replace the "send me a reset link" button by a form (current, new, confirm) calling this endpoint, show
"your other devices were signed out", and a name field calling `PATCH /auth/me`.

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

## SEC-03b: hardening follow-ups (client-visible effects)

* **Public catalog shape.** `GET /product/listing`, `/product/search` and `/product/featured` no longer return `stockQuantity`, `isActive` or `isEnlisted`. Availability is `isOutOfStock` (unchanged); the public queries only ever return active, enlisted products. The storefront already reads only `isOutOfStock` and treats missing `isActive`/`isEnlisted` as true and a missing `stockQuantity` as `null`, so no frontend change is required. `GET /product/admin/listing`, `/product/admin/search` and `/product/main/{id}` keep the full shape (exact stock, flags) for `ADMIN` (search also `FRONT_DESK`). Public responses cached before the deploy may still carry the old fields for up to the cache TTL (10 minutes by default).
* **Swagger / OpenAPI.** `/swagger-ui*` and `/v3/api-docs*` are available only outside prod (the prod profile disables springdoc; the paths answer 401 anonymous).
* **Actuator.** Only `GET`/`HEAD /actuator/health` (status, no details) is public. `/actuator/prometheus` requires `Authorization: Bearer <PROMETHEUS_SCRAPE_TOKEN>` (at least 16 characters; unset = closed). `/actuator/metrics`, `/info`, `/env` and every other actuator path answer 401 to anonymous callers.
* **HEAD.** `HEAD` is allowed on every public `GET` read (same matcher) and follows the role rule on guarded routes.
* **`POST /cart/preview`** for a customer who has no cart row yet now answers `400 Cart is empty, add items and try again.` (it was a `500` "cannot execute INSERT in a read-only transaction"). The preview never creates a cart.
* **`GET /auth/me`** is allowed for any signed-in tenant user (the `@PreAuthorize` is commented out in the controller); a platform token on this route is `401`.
* **Public sort / search.** `GET /product/listing` and `/product/search` accept only `sort` = `id`, `name`, `createdAt` or `price.amount` (the storefront's own sorts); any other property is `400` with `data.code = INVALID_SORT`. Anonymous `searchTerm` matches the product name only (SKU no longer matches). Admin/front-desk endpoints are unchanged.
* **HEAD `/product/{slug}`** does not count a view and is not recorded as a site visit.
* **`GET /product/{slug}` no longer counts views (VIEW-01).** The storefront renders product pages on its server, so per-visitor counting moved to the browser beacon `POST /product/{slug}/view` (rate limited, 30 minute dedupe per tenant+product+client IP or IPv6 /64, bots and prefetch not counted, always `204`). New public read `GET /product/by-ids?ids=` (CAT-07, at most 12 ids, active and listed rows only, no view counted). Contract: [catalog-public-lookup-and-views.md](catalog-public-lookup-and-views.md).
* **WebSocket / STOMP (SEC-03c).** The `/ws` handshake must come from a tenant origin (the session is bound to that tenant); `CONNECT` needs a valid, unexpired token of that tenant (`Authorization: Bearer` STOMP header or the `accessToken` cookie), otherwise the server refuses it and closes the socket; the `ERROR` frame with the reason is best-effort (Spring may drop it when it closes the session right after it), so a client must treat a plain close as a refusal too. A second `CONNECT` on an already authenticated session is refused and the session closed. Staff (`ADMIN`, `FRONT_DESK`) may subscribe to `/user/queue/notifications` only; customers may not subscribe to anything and nobody may `SEND`. Frontend impact: none today (the frontend does not use STOMP); a future admin client must connect from the tenant's own origin and reconnect with a fresh token after expiry.
