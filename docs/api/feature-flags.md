# Feature flags API (API-RD-02: remaining admin rows)

Base path `/api/v1/admin/feature-flags`, **`ADMIN` only** (`@PreAuthorize("hasRole('ADMIN')")` on each handler): anonymous
`401`, `CUSTOMER` / `FRONT_DESK` `403`. Tenant scoped: a flag is stored in the tenant's own schema, so switching it in one shop never
touches another. Bodies are `{ "description": string|null, "data": ... }`. Background and the login flow:
`documentation/auth-otp-feature-flags.md`.

A flag has one on/off value **per audience** (`adminEnabled`, `frontDeskEnabled`, `customerEnabled`); the audience of a
login is the role of the account signing in. Today there is one key.

| `featureKey` | `description` | Effect when on for the account's role |
|---|---|---|
| `LOGIN_OTP` | `Require OTP after successful email/password authentication.` | `POST /auth/login` answers `requiresOtp: true` with a challenge instead of cookies, see [auth-behaviour.md](auth-behaviour.md) |

An audience that has never been set reads as **off** (`false`).

| Method | Path | Purpose |
|---|---|---|
| GET | `/` | every known flag (a list, one entry per key) |
| GET | `/{featureKey}` | one flag |
| PATCH | `/{featureKey}` | set all three audiences at once |

```json feature-flag-response
{
  "description": null,
  "data": {
    "featureKey": "LOGIN_OTP",
    "description": "Require OTP after successful email/password authentication.",
    "adminEnabled": true,
    "frontDeskEnabled": false,
    "customerEnabled": false
  }
}
```

`GET /` returns `data` as a list of the same objects.

## PATCH /{featureKey}

```json feature-flag-request
{ "adminEnabled": true, "frontDeskEnabled": false, "customerEnabled": false }
```

All three booleans are **required** (it replaces the whole flag; there is no partial update, send the current value of the
audiences you do not want to change). The response is the saved flag. Errors:

| Status | When | Body |
|---|---|---|
| 400 | a field missing or `null` | `"Validation failed"` with `data.<field>` |
| 400 | an unreadable body | `Request body is missing or malformed.` |
| 400 | an unknown `featureKey` (path) | `Invalid parameter type` |
| 401 / 403 | not signed in / not `ADMIN` | see above |

Plans (PLAN-03, enforced by PLAN-02): using `LOGIN_OTP` is a plan entitlement. A `PATCH` that turns any audience of
`LOGIN_OTP` **on** for a tenant whose plan lacks the `LOGIN_OTP` feature answers `402 PLAN_UPGRADE_REQUIRED`
(`feature` `LOGIN_OTP`, [plans.md](plans.md#14-402-payment-required)); turning audiences off, or re-sending values that are
already on, is always allowed. A tenant that loses the feature while the flag is on keeps OTP enforced (security is never
weakened silently) until it turns the flag off itself. `GET` is never gated.

Caution for the UI: turning `LOGIN_OTP` on for `adminEnabled` makes the **next** admin login need an e-mail code; confirm
before saving, and make sure mail delivery works first. The change applies to new logins only, not to open sessions.
