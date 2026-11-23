# Team / front-desk staff API (API-RD-11a-d; the team part of API-RD-02)

Base path `/api/v1/admin/front-desk`. Every route is tenant scoped (schema per tenant: another tenant's staff, template
and ETags do not exist for the caller) and `ADMIN` only: anonymous `401`, `CUSTOMER` / `FRONT_DESK` `403` (see
[authorization-matrix.md](authorization-matrix.md)). Bodies are `{ "description": string|null, "data": ... }`.

| Method | Path | Purpose |
|---|---|---|
| GET | `/template` | default permission template; `ETag` header + `data.version` |
| PUT | `/template` | replace the template; optional `If-Match` (API-RD-11c) |
| GET | `/users` | staff list, each with `permissionSummary` (API-RD-11d) |
| POST | `/users` | create a staff member (password policy, API-RD-11b) |
| PATCH | `/users/{userId}/assign` | turn an existing user into front desk |
| PATCH | `/users/{userId}` | edit name / email / password / active flag |
| GET | `/users/{userId}/permissions` | template, overrides, effective set; `ETag` + `data.version` |
| PUT | `/users/{userId}/permissions` | replace the user's overrides; optional `If-Match` (API-RD-11c) |

Permissions (`FrontDeskPermission`, 8 today): `WALK_IN_ORDER_CREATE`, `WALK_IN_ORDER_VIEW`,
`WALK_IN_ORDER_MARK_RECEIPT_PRINTED`, `CUSTOMER_SEARCH`, `PRODUCT_VIEW_ADMIN_CATALOG`, `PRODUCT_VIEW_STOCK_SUMMARY`,
`STOCK_TRANSFER`, `STOCK_CONVERSION`. Effective = template + allowed overrides - denied overrides.

## Error codes (API-RD-11a)

Every business error of these routes now carries a stable `data.code`. **`description` is unchanged, word for word**, so
a client that still matches the text keeps working; new code should branch on `data.code`. Errors raised by bean
validation (blank fields, bad e-mail, unknown permission name, unreadable body) keep their existing shape
(`"Validation failed"` with `data.<field>`, no `code`).

| Status | `data.code` | `description` (exact) | When |
|---|---|---|---|
| 409 | `EMAIL_ALREADY_EXISTS` | `An account with this email already exists.` | create, or PATCH to another account's e-mail |
| 400 | `FULL_NAME_REQUIRED` | `Full name is required.` (create) / `Full name cannot be blank.` (PATCH) | blank name |
| 400 | `EMAIL_REQUIRED` | `Email is required.` | blank e-mail |
| 400 | `PASSWORD_TOO_SHORT` | `Password must be at least 8 characters long` (no full stop, historic) | PATCH, or a create password that is 8+ raw characters but shorter after stripping spaces |
| 400 | `PASSWORD_TOO_WEAK` | `Password must contain at least one uppercase letter, one number, and one special character.` | see policy |
| 400 | `PASSWORD_TOO_LONG` | `Password must be at most 72 bytes long.` | see policy |
| 400 | `NOT_FRONT_DESK_USER` | `Selected user is not a front desk user.` | PATCH / permissions of a non-front-desk user |
| 400 | `PERMISSION_CONFLICT` | `A permission cannot be both allowed and denied.` | one permission in both override sets |
| 404 | `USER_NOT_FOUND` | `User not found.` | unknown id in this tenant |
| 409 | `TEMPLATE_VERSION_CONFLICT` | `The default permissions were changed by someone else. Reload and try again.` | stale `If-Match` on `PUT /template` |
| 409 | `USER_PERMISSIONS_VERSION_CONFLICT` | `This staff member's permissions were changed by someone else. Reload and try again.` | stale `If-Match` on `PUT /users/{id}/permissions` |
| 400 | `INVALID_IF_MATCH` | `If-Match must be the ETag returned by the last read of this resource.` | malformed `If-Match` |

## Staff password policy (API-RD-11b)

Staff accounts (`POST /users`, `PATCH /users/{id}` with a non-blank `password`) now use **the same policy as customer
registration and password reset** (`PasswordPolicy`): evaluated on the stripped password (the value that is stored and
that login compares): at least 8 characters, at most 72 UTF-8 bytes (BCrypt ignores the rest), at least one ASCII
uppercase letter, one digit and one special character from `!@#$%^&*()_+-=[]{};':"\|,.<>/?`. **Exception, unchanged shape:** on `POST /users` a password shorter than 8 raw characters is still refused by bean validation (`"Validation failed"`, `data.password`, no `code`), exactly as before. A refused password writes
nothing. A blank `password` on PATCH still means "leave it unchanged". **Behaviour change:** before, any 8-character
string was accepted; existing staff passwords are not re-checked until they are changed. The frontend's staff form hint
("Use at least 8 characters") should show the full rule.

## Optimistic concurrency (API-RD-11c)

The default template, a user's permission overrides and a tax (see [tax.md](tax.md)) have no version column, and adding
one would need a migration for three differently shaped records. The contract therefore uses a **content token**: the
`ETag` is a SHA-256 prefix (32 hex characters) of the saved state, so it changes exactly when the state changes.

* **Read:** `GET /template` and `GET /users/{id}/permissions` return `ETag: "<token>"` and the same token as `data.version`.
  Every `PUT` response carries the new `ETag` / `data.version`.
* **Save:** send the last token as `If-Match: "<token>"` (quoted, weak `W/"..."` or bare is accepted). The server takes a
  lock (advisory lock per tenant for the template, row lock on the user for permissions), compares the token with the
  state it reads **under that lock**, and only then writes, so of N saves carrying one token exactly one wins; the
  others get `409` with `data.code` as above and `data.currentVersion` (the token to reload against). Nothing is written
  on a `409`.
* **Header absent (or `*`):** the save is last-write-wins exactly as before. **Backward compatible on purpose; deprecated.**
  New clients should always send `If-Match`. A malformed value is `400 INVALID_IF_MATCH`.
* The permission token covers the user's **overrides only**, not the template: changing the default template does not
  invalidate a screen that is saving overrides (the response still reports the current template and effective set).
* A repeated identical save is not a conflict by itself: the token is compared with the state, not with a counter. If
  a state returns to an earlier value (A, B, A), the old token is valid again; the saved result is the same state.

```json template-response
{
  "description": null,
  "data": {
    "permissions": ["CUSTOMER_SEARCH", "WALK_IN_ORDER_CREATE", "WALK_IN_ORDER_VIEW"],
    "version": "0f1e2d3c4b5a69788796a5b4c3d2e1f0"
  }
}
```

```json user-permissions-response
{
  "description": null,
  "data": {
    "userId": 5,
    "templatePermissions": ["CUSTOMER_SEARCH", "WALK_IN_ORDER_CREATE", "WALK_IN_ORDER_VIEW"],
    "allowedOverrides": ["STOCK_TRANSFER"],
    "deniedOverrides": ["CUSTOMER_SEARCH"],
    "effectivePermissions": ["STOCK_TRANSFER", "WALK_IN_ORDER_CREATE", "WALK_IN_ORDER_VIEW"],
    "version": "a1b2c3d4e5f60718293a4b5c6d7e8f90"
  }
}
```

## Per-user permission summary (API-RD-11d)

`GET /users` (and the other single-user answers) now carry `permissionSummary`, so the list can show "Custom 6 of 8"
without one call per user. The list costs three queries (the staff, the template once, every user's overrides once), not one per user.

| Field | Meaning |
|---|---|
| `effectiveCount` | permissions the user can use now |
| `totalCount` | all front-desk permissions that exist (8 today) |
| `allowedOverrideCount` | permissions granted by an override that the template does not grant |
| `deniedOverrideCount` | permissions the template grants that an override removes |
| `isCustom` | `true` when the effective set differs from the default template |

Overrides that change nothing (allow what the template already allows, deny what it does not grant) count as neither,
so a user is "Default" until their effective set really differs. Only front-desk users carry it (`null` for any other role).

```json staff-list
{
  "description": null,
  "data": [
    {
      "id": 5, "fullName": "Ama Desk", "email": "ama@shop.example", "isActive": true, "role": "FRONT_DESK",
      "permissionSummary": { "effectiveCount": 6, "totalCount": 8, "allowedOverrideCount": 3, "deniedOverrideCount": 0, "isCustom": true }
    }
  ]
}
```

## Frontend follow-up

`src/lib/admin/team/errors.ts` (description matching) can switch to `data.code`; `StaffModal.tsx` password hint should
show the policy; `DefaultPermissions.tsx` / `PermissionsModal.tsx` should keep the `ETag` of their GET and send it as
`If-Match`, handling `TEMPLATE_VERSION_CONFLICT` / `USER_PERMISSIONS_VERSION_CONFLICT` by reloading; `TeamTab.tsx` can
read `permissionSummary` instead of fetching each user's permissions.
