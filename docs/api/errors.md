# Error responses and error logs (SEC-01d)

Every error answer uses the standard envelope `{"description": <text>, "data": <object|null>}`. Clients branch on the
HTTP status and, where documented, on `data.code`; `description` is display text only.

**Rule:** no response body carries the text of a framework, driver, JDK or library exception (it can quote database
row values such as Postgres `Key (email)=(..) already exists`, a JDBC URL, credentials or the request itself).
Those cases answer the fixed descriptions below. Messages of our own exception types are deliberate, author-written
business messages and are kept. Status codes and `data.code`s did not change.

## Fixed descriptions

| Status | Thrown | `description` (exact) | Before SEC-01d |
|---|---|---|---|
| 500 | `TransactionSystemException` (not transient) | `Transaction failed` | the root cause's message |
| 500 | `AccountingException` (ledger could not record the operation; the transaction rolled back, nothing saved) | `The operation could not be recorded in the ledger and was not saved. Nothing was changed; try again or contact support.` with `data.code` `ACCOUNTING_ERROR` | HTTP 200 with an empty body (API-RD-13a) |
| 500 | anything unhandled | `Unexpected error occurred` | unchanged |
| 500 | `InputOutputException` | `File processing failed` | its message |
| 500 | `ServiceCommunicationException` | `Interservice communication failed` | unchanged |
| 409 | `DataIntegrityViolationException` | `A constraint violation occurred` | unchanged (now also logged, see below). Product create: a family-code / base-SKU clash is retried once and then answers `FAMILY_CODE_UNAVAILABLE` instead (ORD-02c, [catalog.md](catalog.md)) |
| 405 | `UnsupportedOperationException` | `Operation not supported` | its message |
| 405 | `HttpRequestMethodNotSupportedException` | `Request method not supported` | Spring's message (echoed the method) |
| 404 | `NoResourceFoundException` | `Resource not found` | Spring's message (echoed the path) |
| 400 | `IllegalArgumentException` from a library / the JDK, or with a cause | `Invalid request` | its message |
| 400 | `MissingRequestCookieException` | `Missing required cookie: <name>.` | Spring's message |
| 400 | `MissingServletRequestPartException` | `Missing required part: <name>.` | Spring's message |
| 400 | `BindException`, field with a conversion failure | `data.<field>` = `Invalid value` | the conversion message (quoted the value and Java types) |
| 401 | `ExpiredJwtException` | `Token has expired` | jjwt's message (expiry and server clock) |
| 401 | security entry point (any `AuthenticationException`) | `Full authentication is required to access this resource` | the exception's message |
| 403 | `AuthorizationDeniedException` | `Access Denied` | its message (Spring's own was already `Access Denied`) |
| any | Spring Boot `/error` (filter errors, `sendError`) | Boot's default body without `message`, `errors`, `exception`, `trace` | same (now pinned, see below). The container's `/error` dispatch is permitted for every caller (SEC-01f), so the original status reaches the client: a filter exception is `500`, no tenant `400`, unknown tenant `404`, suspended tenant `423`. Before, all of them were rewritten to `401` by the anonymous `/error` dispatch. |

## Walk-in cancel / refund codes (API-RD-03 AC3)

`POST /api/v1/admin/walk-in/orders/{orderNumber}/cancel|refund` answer with `ApiCodeException` bodies
(`{"description", "data": {"code", ...}}`): `400 IDEMPOTENCY_KEY_REQUIRED` / `IDEMPOTENCY_KEY_INVALID`,
`409 IDEMPOTENCY_KEY_REUSED`, `409 WALK_IN_ORDER_NOT_CANCELLABLE` / `WALK_IN_ORDER_NOT_REFUNDABLE` (with
`data.currentStatus`), `409 INSUFFICIENT_CASH`, `409 WALK_IN_STOCK_NOT_RESTORABLE`, and the existing `500 ACCOUNTING_ERROR`.
Plain `400` (bad or extra body property), `403` (not ADMIN) and `404` (unknown order, or another tenant's) carry no code.
Full table and semantics: [walk-in.md](walk-in.md#cancel-and-refund-api-rd-03-ac3). The reason text typed by the admin
is stored in the audit table and never appears in logs or error bodies.

## Plans and subscription (PLAN-03)

The 404 / 409 / 400 plan codes below are implemented by PLAN-01 (platform plan API and tenant plan action); the two 402 codes are PLAN-02. Full rules, examples and precedence in [plans.md](plans.md#14-402-payment-required).
All are `ApiCodeException` bodies `{"description", "data": {"code", ...}}`.

| Status | `data.code` | Extra `data` keys | When |
|---|---|---|---|
| 402 | `PLAN_UPGRADE_REQUIRED` | `feature`, `reason` (`NOT_IN_PLAN` \| `SUBSCRIPTION_INACTIVE`), `requiredPlans` (plan codes) | a tenant route needs a feature its plan lacks, or a gated write / limit-checked create while the trial has ended or the subscription is `CANCELED` |
| 402 | `PLAN_LIMIT_REACHED` | `feature`, `limit`, `used`, `requiredPlans` | any write that moves a row into a counted set (product create, coupon create or activation, front-desk user create / re-activation / assign, storage location create) at the plan's limit |
| 409 | `PLAN_VERSION_CONFLICT` | `conflicts[]` = `{planId, expectedVersion, currentVersion}` | a platform plan write (PUT, matrix) carried a stale `version`; nothing was written. Plan writes never use the generic `503 TRY_AGAIN` optimistic-lock answer |
| 409 | `PLAN_CODE_TAKEN` | | plan create with an existing code |
| 404 | `PLAN_NOT_FOUND` | | unknown plan id |
| 400 | `PLAN_INACTIVE` | | assigning an inactive plan to a tenant without `allowInactive: true` |
| 400 | `TRIAL_NOT_STARTED` | | `trialEndsAt` sent for a tenant whose trial has not started (not provisioned yet, `trialEndsAt` null) |

402 is new in this API. Precedence on a tenant route: tenant resolution (400 / 404 / 423 suspended) -> 401 -> 403
(including the service-level front-desk permission checks) -> 400 body parsing / bean validation (`@Valid` runs at
argument binding) -> 402 plan gate -> the service's own business checks (which may also answer 400 / 409). Every 403
precedes every 402. The plan gate, the body-dependent gates (`THEME_ADVANCED`, `LOGIN_OTP` off -> on,
`PRODUCT_FAMILIES` fields) and the limit checks all run in the service after authorization
([plans.md](plans.md#144-precedence)). Finishing work on existing orders (walk-in cancel / refund) and returns and refunds
(core for every plan) are never a 402.
The admin frontend opens one upgrade modal for any 402 and never shows it as a generic error.

## Kept: business messages (our own exception types)

`ApiCodeException` (with `data.code`), `BadRequestException`, `CouponRejectedException`, `ValidationException`,
`DuplicateResourceException`, `ResourceNotFoundException`, `ForbiddenException`, `UnauthorizedException`,
`InvalidJWTTokenException`, `InactiveAccountException`, `RateLimitExceededException`, `PaymentException` (its texts are
fixed since SEC-01d, see Paystack below), `PartialSuccessException`, `InvalidFileException`, `FileUploadException`,
the platform `IllegalTenantTransitionException` / `TenantProvisioningFailedException` / `TenantMigrationFailedException`
(slug only; the platform console matches the provisioning text), and the theme handler's own fixed texts.

`BadRequestException`, `DuplicateResourceException` and `ResourceNotFoundException` may carry an optional stable code
(API-RD-11a): then the body is `{"description": <same text as before>, "data": {"code": <CODE>}}`; without a code it
stays `data: null`. Team and tax routes use it ([team-staff.md](team-staff.md), [tax.md](tax.md)); status and
`description` of every case are unchanged.

Also kept: `IllegalArgumentException` thrown by our own code (top stack frame in `com.example.perfume_budget`, no cause;
e.g. `Invalid or expired token`, `Malformed ETag`); bean-validation messages (`ConstraintViolationException`,
`HandlerMethodValidationException`, `BindException` validation errors: annotation texts); Spring Security's
`BadCredentialsException` / `DisabledException` (our constants or Spring's fixed `Bad credentials` /
`User is disabled`); `Missing required parameter: <name>.`; the existing fixed texts (sort, date format, body
missing or malformed, upload size, optimistic lock, `TRY_AGAIN`).

## Configuration (base `application.properties`, inherited by every profile)

```
logging.level.org.hibernate.engine.jdbc.spi.SqlExceptionHelper=OFF
server.error.include-message=never
server.error.include-binding-errors=never
server.error.include-stacktrace=never
server.error.include-exception=false
```

Hibernate's `SqlExceptionHelper` logs the driver's error text (Postgres `Detail: Key (email)=(..)`) at WARN/ERROR, so it
is off. No profile or test properties file may set these keys again, and there is no logback file
(`ErrorExposurePropertiesTest`). Do not set `LOGGING_LEVEL_ORG_HIBERNATE_ENGINE_JDBC_SPI_SQLEXCEPTIONHELPER` in a deployment.

## What our logs record for a database error

Only the SQLState and the violated constraint's name (`utils/DbErrorDetails`), both checked against strict character
sets, never the message or `Detail`:

- a 409 constraint violation: `WARN ... Constraint violation answered 409: SQLState 23505, constraint uc_users_email`;
- every ERROR stack trace written through `SafeStackTrace` (500 handlers, webhook settlement, scheduled jobs, ...):
  the database exception's line reads e.g.
  `org.hibernate.exception.ConstraintViolationException [SQLState 23505, constraint uc_users_email]`.

## Paystack

`PaystackGatewayServiceImpl` never logs a Paystack response body (it can carry the customer's e-mail, authorization /
access codes and card metadata). It logs the HTTP status, our order number and Paystack's top-level `status`,
`message` (e-mail addresses and digit runs of 4+ replaced by `[redacted]`, control characters flattened, max 160
chars), `type` and `code` (plain lower-case identifiers only). The `PaymentException` texts (answered to the client
and stored as the payment's failure reason) are fixed: `Invalid payment request`, `Payment request failed`,
`Payment initialization failed`, ... `NoPersonalDataInLogsTest` rejects a raw body (`getBody()`, `.body()`,
`getResponseBodyAsString()`, a `*body` variable) passed to a log call at INFO or above.

## Cache read failures are counted (FIX-A2)

A cache entry that cannot be read (Redis down, an entry of an old serialised shape) is treated as a miss and the request
still succeeds, with a WARN line. Every such failure also increments the Micrometer counter `shopfi.cache.errors`
(Prometheus `shopfi_cache_errors_total`, tags `cache` and `op` = `get|put|evict|clear`). `prometheus-alerts.yml` alerts
`CacheReadErrors` when any `op="get"` failure happened in the last 5 minutes for 5 minutes, and
`CacheWriteOrEvictErrors` for put/evict/clear failures (an evict or clear failure can serve stale data until the TTL).
