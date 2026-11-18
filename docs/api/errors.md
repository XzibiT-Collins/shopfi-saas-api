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
| 500 | anything unhandled | `Unexpected error occurred` | unchanged |
| 500 | `InputOutputException` | `File processing failed` | its message |
| 500 | `ServiceCommunicationException` | `Interservice communication failed` | unchanged |
| 409 | `DataIntegrityViolationException` | `A constraint violation occurred` | unchanged (now also logged, see below) |
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
| any | Spring Boot `/error` (filter errors, `sendError`) | Boot's default body without `message`, `errors`, `exception`, `trace` | same (now pinned, see below) |

## Kept: business messages (our own exception types)

`ApiCodeException` (with `data.code`), `BadRequestException`, `CouponRejectedException`, `ValidationException`,
`DuplicateResourceException`, `ResourceNotFoundException`, `ForbiddenException`, `UnauthorizedException`,
`InvalidJWTTokenException`, `InactiveAccountException`, `RateLimitExceededException`, `PaymentException` (its texts are
fixed since SEC-01d, see Paystack below), `PartialSuccessException`, `InvalidFileException`, `FileUploadException`,
the platform `IllegalTenantTransitionException` / `TenantProvisioningFailedException` / `TenantMigrationFailedException`
(slug only; the platform console matches the provisioning text), and the theme handler's own fixed texts.

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
