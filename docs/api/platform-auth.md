# Platform super-admin account API (PLAT-01 + API-RD-07)

All endpoints are under `/api/v1/platform/auth`, run in the PLATFORM scope and read/write only `master.platform_admins`.
Cookies: `platformAccessToken` (HttpOnly, path `/api/v1/platform`) and `platformRefreshToken` (path `/api/v1/platform/auth`);
tenant cookies and tokens never authenticate here and platform cookies never authenticate on tenant routes. Browsers must call
from an origin in `platform.cors.allowed-origins`. Error bodies are `{"description": "<message>", "data": null}` (or with
`data.code`, below).

| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /login`, `POST /refresh`, `POST /logout` | public (throttled) | PLAT-01 sign-in, refresh, sign-out |
| `GET /me` | SUPER_ADMIN | the signed-in admin |
| `POST /change-password` | SUPER_ADMIN | API-RD-07 AC1, below |

## `POST /api/v1/platform/auth/change-password` (API-RD-07 AC1)

```json example:platform-change-password-request
{ "currentPassword": "Sup3r-Admin-pass!", "newPassword": "Br4nd-New-Pass!" }
```

Success: `200` `{"description": "Password changed", "data": {"email": "...", "fullName": "...", "role": "SUPER_ADMIN"}}` and fresh
`platformAccessToken` / `platformRefreshToken` cookies for the calling browser.

* The target is always the signed-in admin (the principal); `email` / `id` in the body are ignored. Anonymous, a tenant token of any role
  (ADMIN, CUSTOMER, FRONT_DESK), a refresh token used as access token, and a deactivated admin: `401`.
* `currentPassword` is verified first (one BCrypt compare). Wrong: `400`
  `{"description": "Current password is incorrect", "data": {"code": "CURRENT_PASSWORD_INCORRECT"}}`; nothing changes, and a weak
  `newPassword` is not reported in that case. (`400`, not `401`, so the console does not treat it as an expired session.)
* `newPassword` follows the sign-up / reset policy of the tenant API (8+ characters, at most 72 bytes, one `A-Z`, one `0-9`,
  one special; evaluated stripped). Weak: `400` with the policy message. Equal to the current password: `400`
  `New password cannot be same as current password`. Both fields are capped at 72 characters / bytes (`400`).
* **Token revocation (the PLAT-03 AC1 model).** `platform_admins.token_version` (master migration V50) is minted into every
  access and refresh token as the `tv` claim (no claim = version 0). A successful change bumps it in the same transaction as the
  new hash, with the row locked, so **every access and refresh token minted before the change stops working on every browser**:
  `401` on every platform route and on `POST /refresh`. Other admins' sessions are unaffected. Concurrent changes serialise:
  exactly one wins.
* **Rate limit** (guesses the current password): every attempt consumes a token from a per-IP bucket
  (`platform.auth.change-password-ip`, default 10 per 900 s) and a per-admin bucket (`platform.auth.change-password-admin`,
  default 5 per 900 s). Over the limit: `429` + `Retry-After` (also for the right password).
* CSRF: the call needs the current password, which a cross-site request cannot know, in addition to the origin allow-list.

| Status | When |
|---|---|
| 200 | changed, session re-issued |
| 400 | validation, wrong current password, policy, reuse |
| 401 | no / invalid / revoked / foreign credentials, deactivated admin |
| 429 | rate limited |

## Active sessions view: decision, not built (API-RD-07 AC1)

There is **no endpoint that lists a platform admin's sessions**, by decision. The token model is stateless: access and refresh
JWTs carry no session id (`jti`) and the server keeps no session table, so there is nothing true to list ("which browsers hold a
valid refresh token" is not recorded, and a list derived from login events would show sessions that were already revoked or
expired). Building it needs a session store (a `platform_sessions` table or a jti allow-list with refresh rotation, which is the
other half of PLAT-03 AC1) and is deliberately left to that ticket. What the console gets now is the useful half:
**"sign out everywhere" = change password** (revokes all other sessions at once). A cheaper variant, a "sign out all devices"
button that bumps `token_version` without changing the password, can be added on the same column when asked for.

Limitations that remain (PLAT-03, not part of this ticket): `POST /logout` only clears the caller's cookies and does not revoke
the refresh token (a copy stays valid until its 12 h expiry or the next password change); there is no deactivate endpoint.
