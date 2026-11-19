# Authorization matrix (SEC-03)

Verified against reflection and `SecurityConfig` (SEC-03b). `AuthorizationMatrixStructureTest` pins every controller handler (any verb, including `@RequestMapping(method = GET)`) to its intended `@PreAuthorize` expression in a hand-written table, cross-checks the `SecurityConfig` matchers against it, and checks every row of section 3 below (controller, verb, path, matcher kind and roles, `@PreAuthorize`) against the source; `CatalogAuthorizationMatrixIntegrationTest` exercises the real filter chain with six actors (anonymous, CUSTOMER, FRONT_DESK, ADMIN, foreign-tenant ADMIN, platform token).

## Model

* `SecurityConfig` is **deny by default**: `anyRequest().authenticated()`. The only `permitAll` entries are method-specific (`GET`/`POST`) and listed in section 2. A new route is authenticated until someone adds an explicit rule.
* Mutations and admin reads on category/product are `hasRole("ADMIN")` at matcher level **and** `@PreAuthorize` on the controller method (defence in depth).
* Tenant is resolved by `TenantResolutionFilter` (Origin/Referer; dev-only `X-Tenant-ID`). Per AUTH-01 a cookie or token minted for another tenant, or a platform token on a tenant route, is treated as **anonymous**.

## 1. Responses by actor (tenant routes)

| Actor | Protected route | Public route |
|---|---|---|
| anonymous | 401 JSON (entry point) | normal response |
| foreign-tenant ADMIN token / cookie | 401 (it is anonymous) | normal response, requesting tenant only |
| platform SUPER_ADMIN token on a tenant route | 401 (anonymous) | normal response |
| CUSTOMER / FRONT_DESK without the role | 403 JSON (access denied handler); FRONT_DESK is allowed only where the matrix lists it | normal response |
| ADMIN of the tenant | allowed | normal response |

**Behaviour change (SEC-03):** an anonymous (or foreign-tenant) call to a category/product route that needs ADMIN used to return **403** (method security fired after the permitAll matcher, mapped by `GlobalExceptionHandler`); it now returns **401**, like every other protected route and like `PUT /api/v1/theme`. A signed-in non-admin still gets 403. Clients should treat 401 as "sign in / refresh once", 403 as "forbidden".

## 2. Public allow-list (permitAll)

| Method + path | Why public |
|---|---|
| `GET /api/v1/category/all`, `GET /api/v1/product/{featured,listing,search}`, `GET /api/v1/product/{slug}`, `GET /api/v1/theme` | storefront reads (tenant from Origin) |
| `POST /api/v1/auth/{register,verify-otp,verify-login-otp,resend-otp,resend-login-otp,login,logout,refresh,forgot-password,reset-password}` | sign-in flow; `GET /auth/me` is authenticated; any other `/auth/**` is authenticated |
| `POST /api/v1/webhook/{token}` | Paystack; per-tenant token + HMAC signature (TENANT-05). Other methods: 401 |
| `POST /api/v1/platform/auth/{login,refresh,logout}`, `POST /api/v1/platform/onboarding/{signup,verify,resend}`, `GET /api/v1/platform/onboarding/status` | platform sign-in and public onboarding; every other `/platform/**` is `SUPER_ADMIN` |
| `/oauth2/**`, `/login/oauth2/**` | OAuth2 login redirects |
| `/ws/**` | SockJS/STOMP handshake (HTTP level only; every STOMP frame is authorised, see section 2a): Origin checked by `TenantOriginHandshakeInterceptor`, tenant bound by `TenantHandshakeInterceptor` |
| `GET /swagger-ui/**`, `/swagger-ui.html`, `/v3/api-docs/**` | dev/test only: permitted only while `springdoc.*.enabled` is true; the prod profile disables springdoc and the matchers then require authentication (anonymous 401) |
| `GET /actuator/health` | liveness probe (Render health check); status only, details are `never` |
| `GET /actuator/prometheus` | NOT public: needs `Authorization: Bearer <shopfi.metrics.scrape-token>` (env `PROMETHEUS_SCRAPE_TOKEN`, at least 16 characters); with no token configured it is closed (401). Every other `/actuator/**` path (metrics, info, env, ...) is `denyAll` (401 anonymous) |
| `HEAD` on every public `GET` above | permitted with the same matcher (HEAD is answered from the GET handler); `HEAD` on an admin route follows the admin rule |
| `/api/v1/dev/**` | **denyAll** unless `tenant.dev-endpoints.enabled=true` (true only in the dev profile; prod sets it false; the controller bean is also conditional on it) |

## 2a. STOMP (`/ws`) authorization (SEC-03c / WS-01)

The handshake resolves the tenant with the HTTP rules (`RequestTenantResolver`: Origin, then Referer, then the non-prod `X-Tenant-ID` header) and stores it in the session attributes. Every frame is then checked by `AuthChannelInterceptor`; a refused frame never reaches the broker and the session is closed. The STOMP `ERROR` frame (header `message` = the reason below; any other processing error says only `Bad request`) is best-effort: Spring closes the session right after sending it and `ConcurrentWebSocketSessionDecorator` may drop the still-buffered frame, so a client may see only the close.

| Step | Allowed | Refused with |
|---|---|---|
| Handshake | Origin allowed by `TenantOriginPolicy` AND resolving to an ACTIVE tenant | disallowed Origin `403`; unknown tenant / not active `404`; suspended `423`; no tenant signal at all `400` |
| `CONNECT` / `STOMP` | on a session that is not yet authenticated: a token (`Authorization: Bearer` native header, else the `accessToken` cookie of the handshake) that verifies, is unexpired, has `tenant` = the handshake tenant, and names an enabled user of that tenant (looked up with that tenant bound) | `Authentication required` (no token), `Token does not belong to this tenant` (other tenant, platform or missing claim), `Invalid or expired token`; `Already connected` (a second CONNECT on an authenticated session; the principal is never swapped. Over a WebSocket Spring refuses it even earlier, so the client sees `Bad request` or just the close) |
| `SUBSCRIBE` | exactly `/user/queue/notifications` (case-sensitive, no trailing slash), for `ADMIN` / `FRONT_DESK` | `Destination not allowed` (any other destination, any wildcard, case or trailing-slash variant, any customer); `Authentication required`; `Session expired` once the CONNECT token's own `exp` has passed |
| `SEND` | nothing (there are no `@MessageMapping` handlers) | `Destination not allowed` |
| other frames (`UNSUBSCRIBE`, `ACK`, `NACK`, `DISCONNECT`, transactions) | passed through | |

Pushes use `convertAndSendToUser("<tenant>:<email>", "/queue/notifications", ...)`: the session principal's name is tenant-qualified, and `AdminSessionManager` keys connected staff by tenant, so a notification of tenant A never reaches a session of tenant B, even for the same e-mail.

## 3. Every controller endpoint

| Controller | Method | Path | Matcher | @PreAuthorize | Effect |
|---|---|---|---|---|---|
| AccountingController | GET | `/api/v1/admin/accounting/summary` | authenticated (anyRequest) | `hasRole('ADMIN')` (class) | leaks tenant financials; **was open to any signed-in user, fixed** |
| AccountingController | GET | `/api/v1/admin/accounting/income-statement` | authenticated (anyRequest) | `hasRole('ADMIN')` (class) | leaks tenant financials; **was open to any signed-in user, fixed** |
| AccountingController | GET | `/api/v1/admin/accounting/balance-sheet` | authenticated (anyRequest) | `hasRole('ADMIN')` (class) | leaks tenant financials; **was open to any signed-in user, fixed** |
| AccountingController | GET | `/api/v1/admin/accounting/cash-flow` | authenticated (anyRequest) | `hasRole('ADMIN')` (class) | leaks tenant financials; **was open to any signed-in user, fixed** |
| AccountingController | GET | `/api/v1/admin/accounting/journal-entries` | authenticated (anyRequest) | `hasRole('ADMIN')` (class) | leaks tenant financials; **was open to any signed-in user, fixed** |
| AccountingController | GET | `/api/v1/admin/accounting/journal-entries/reference` | authenticated (anyRequest) | `hasRole('ADMIN')` (class) | leaks tenant financials; **was open to any signed-in user, fixed** |
| AccountingController | GET | `/api/v1/admin/accounting/journal-entries/manual` | authenticated (anyRequest) | `hasRole('ADMIN')` (class) | leaks tenant financials; **was open to any signed-in user, fixed** |
| AccountingController | GET | `/api/v1/admin/accounting/metadata` | authenticated (anyRequest) | `hasRole('ADMIN')` (class) | leaks tenant financials; **was open to any signed-in user, fixed** |
| AdminFrontDeskManagementController | GET | `/api/v1/admin/front-desk/template` | authenticated (anyRequest) | `hasRole('ADMIN')` | reads tenant data |
| AdminFrontDeskManagementController | PUT | `/api/v1/admin/front-desk/template` | authenticated (anyRequest) | `hasRole('ADMIN')` | mutates |
| AdminFrontDeskManagementController | GET | `/api/v1/admin/front-desk/users` | authenticated (anyRequest) | `hasRole('ADMIN')` | reads tenant data |
| AdminFrontDeskManagementController | POST | `/api/v1/admin/front-desk/users` | authenticated (anyRequest) | `hasRole('ADMIN')` | mutates |
| AdminFrontDeskManagementController | PATCH | `/api/v1/admin/front-desk/users/{userId}/assign` | authenticated (anyRequest) | `hasRole('ADMIN')` | mutates |
| AdminFrontDeskManagementController | PATCH | `/api/v1/admin/front-desk/users/{userId}` | authenticated (anyRequest) | `hasRole('ADMIN')` | mutates |
| AdminFrontDeskManagementController | GET | `/api/v1/admin/front-desk/users/{userId}/permissions` | authenticated (anyRequest) | `hasRole('ADMIN')` | reads tenant data |
| AdminFrontDeskManagementController | PUT | `/api/v1/admin/front-desk/users/{userId}/permissions` | authenticated (anyRequest) | `hasRole('ADMIN')` | mutates |
| AdminMetricController | GET | `/api/v1/admin/metrics/customers` | authenticated (anyRequest) | `hasRole('ADMIN')` | reads tenant data |
| AdminMetricController | GET | `/api/v1/admin/metrics/customers/{userId}` | authenticated (anyRequest) | `hasRole('ADMIN')` | reads tenant data |
| AdminMetricController | GET | `/api/v1/admin/metrics/customers/{userId}/orders` | authenticated (anyRequest) | `hasRole('ADMIN')` | reads tenant data |
| AdminMetricController | GET | `/api/v1/admin/metrics/coupons` | authenticated (anyRequest) | `hasRole('ADMIN')` | reads tenant data |
| AdminMetricController | GET | `/api/v1/admin/metrics/top-products` | authenticated (anyRequest) | `hasRole('ADMIN')` | reads tenant data |
| AdminMetricController | GET | `/api/v1/admin/metrics/site-metric` | authenticated (anyRequest) | `hasRole('ADMIN')` | reads tenant data |
| AdminMetricController | GET | `/api/v1/admin/metrics/dashboard-metric` | authenticated (anyRequest) | `hasRole('ADMIN')` | reads tenant data |
| AdminMetricController | GET | `/api/v1/admin/metrics/low-stock-products` | authenticated (anyRequest) | `hasRole('ADMIN')` | reads tenant data |
| AdminMetricController | GET | `/api/v1/admin/metrics/top-customers` | authenticated (anyRequest) | `hasRole('ADMIN')` | reads tenant data |
| AdminMetricController | GET | `/api/v1/admin/metrics/sales-analytics` | authenticated (anyRequest) | `hasRole('ADMIN')` | reads tenant data |
| AuthController | POST | `/api/v1/auth/register` | permitAll (explicit POST) | - | mutates |
| AuthController | POST | `/api/v1/auth/verify-otp` | permitAll (explicit POST) | - | mutates |
| AuthController | POST | `/api/v1/auth/verify-login-otp` | permitAll (explicit POST) | - | mutates |
| AuthController | POST | `/api/v1/auth/resend-otp` | permitAll (explicit POST) | - | mutates |
| AuthController | POST | `/api/v1/auth/resend-login-otp` | permitAll (explicit POST) | - | mutates |
| AuthController | POST | `/api/v1/auth/login` | permitAll (explicit POST) | - | mutates |
| AuthController | GET | `/api/v1/auth/me` | authenticated (explicit) | - | reads own profile; the `@PreAuthorize` in the source is commented out, so any signed-in tenant user (ADMIN, CUSTOMER, FRONT_DESK) passes; a platform token on this tenant route is anonymous -> 401 |
| AuthController | POST | `/api/v1/auth/logout` | permitAll (explicit POST) | - | mutates |
| AuthController | POST | `/api/v1/auth/refresh` | permitAll (explicit POST) | - | mutates |
| AuthController | POST | `/api/v1/auth/forgot-password` | permitAll (explicit POST) | - | mutates |
| AuthController | POST | `/api/v1/auth/reset-password` | permitAll (explicit POST) | - | mutates |
| BookkeepingController | POST | `/api/v1/admin/bookkeeping/manual-entry` | authenticated (anyRequest) | `hasRole('ADMIN')` | mutates |
| CartController | GET | `/api/v1/cart` | authenticated (anyRequest) | `hasRole('CUSTOMER')` | reads tenant data |
| CartController | DELETE | `/api/v1/cart/clear` | authenticated (anyRequest) | `hasRole('CUSTOMER')` | mutates |
| CartController | POST | `/api/v1/cart/populate` | authenticated (anyRequest) | `hasRole('CUSTOMER')` | mutates |
| CartController | POST | `/api/v1/cart/checkout` | authenticated (anyRequest) | `hasRole('CUSTOMER')` | mutates |
| CartController | POST | `/api/v1/cart/preview` | authenticated (anyRequest) | `hasRole('CUSTOMER')` | mutates |
| CartItemController | POST | `/api/v1/cart/items/add-item` | authenticated (anyRequest) | `hasRole('CUSTOMER')` | mutates |
| CartItemController | PUT | `/api/v1/cart/items/update/{cartItemId}` | authenticated (anyRequest) | `hasRole('CUSTOMER')` | mutates |
| CartItemController | DELETE | `/api/v1/cart/items/remove/{cartItemId}` | authenticated (anyRequest) | `hasRole('CUSTOMER')` | mutates |
| CategoryController | POST | `/api/v1/category/add-category` | ADMIN | `hasRole('ADMIN')` | mutates |
| CategoryController | PUT | `/api/v1/category/update/{categoryId}` | ADMIN | `hasRole('ADMIN')` | mutates |
| CategoryController | GET | `/api/v1/category/all` | permitAll (GET) | - | reads tenant data (public storefront data) |
| CategoryController | GET | `/api/v1/category/{categoryId}` | ADMIN | `hasRole('ADMIN')` | reads tenant data |
| CategoryController | DELETE | `/api/v1/category/delete/{categoryId}` | ADMIN | `hasRole('ADMIN')` | mutates; **was anonymous, fixed** |
| CouponController | POST | `/api/v1/coupon/add-coupon` | authenticated (anyRequest) | `hasRole('ADMIN')` | mutates |
| CouponController | GET | `/api/v1/coupon/{couponId}` | authenticated (anyRequest) | `hasRole('ADMIN')` | reads tenant data |
| CouponController | GET | `/api/v1/coupon/all` | authenticated (anyRequest) | `hasRole('ADMIN')` | reads tenant data |
| CouponController | PUT | `/api/v1/coupon/{couponId}` | authenticated (anyRequest) | `hasRole('ADMIN')` | mutates (API-04: full replace, code immutable) |
| CouponController | POST | `/api/v1/coupon/{couponId}/deactivate` | authenticated (anyRequest) | `hasRole('ADMIN')` | mutates (API-04: revoke, immediate) |
| CouponController | DELETE | `/api/v1/coupon/{couponId}` | authenticated (anyRequest) | `hasRole('ADMIN')` | mutates (API-04: never-redeemed coupons only) |
| DeliveryDetailController | POST | `/api/v1/delivery-detail/add-address` | authenticated (anyRequest) | `hasRole('CUSTOMER')` | mutates |
| DeliveryDetailController | PUT | `/api/v1/delivery-detail/update/{addressId}` | authenticated (anyRequest) | `hasRole('CUSTOMER')` | mutates |
| DeliveryDetailController | DELETE | `/api/v1/delivery-detail/delete/{addressId}` | authenticated (anyRequest) | `hasRole('CUSTOMER')` | mutates |
| DeliveryDetailController | GET | `/api/v1/delivery-detail` | authenticated (anyRequest) | `hasRole('CUSTOMER')` | reads tenant data |
| DeliveryDetailController | PUT | `/api/v1/delivery-detail/{addressId}/set-default` | authenticated (anyRequest) | `hasRole('CUSTOMER')` | mutates |
| DevTenantController | POST | `/api/v1/dev/tenants` | denyAll unless `tenant.dev-endpoints.enabled=true` | - | mutates master (provisions tenant); dev only |
| FeatureFlagController | GET | `/api/v1/admin/feature-flags` | authenticated (anyRequest) | `hasRole('ADMIN')` | reads tenant data |
| FeatureFlagController | GET | `/api/v1/admin/feature-flags/{featureKey}` | authenticated (anyRequest) | `hasRole('ADMIN')` | reads tenant data |
| FeatureFlagController | PATCH | `/api/v1/admin/feature-flags/{featureKey}` | authenticated (anyRequest) | `hasRole('ADMIN')` | mutates |
| InventoryManagementController | POST | `/api/v1/admin/inventory/receipts` | authenticated (anyRequest) | `hasRole('ADMIN')` | mutates |
| InventoryManagementController | POST | `/api/v1/admin/inventory/adjustments` | authenticated (anyRequest) | `hasRole('ADMIN')` | mutates |
| InventoryManagementController | GET | `/api/v1/admin/inventory/products/{productId}/summary` | authenticated (anyRequest) | `hasRole('ADMIN')` | reads tenant data |
| InventoryManagementController | GET | `/api/v1/admin/inventory/products/{productId}/stock-by-location` | authenticated (anyRequest) | `hasAnyRole('ADMIN','FRONT_DESK')` | reads tenant data |
| InventoryManagementController | GET | `/api/v1/admin/inventory/products/{productId}/history` | authenticated (anyRequest) | `hasRole('ADMIN')` | reads tenant data |
| NotificationController | GET | `/api/v1/admin/notifications` | authenticated (anyRequest) | `hasAnyRole('ADMIN','FRONT_DESK')` | reads tenant data |
| NotificationController | GET | `/api/v1/admin/notifications/unread-count` | authenticated (anyRequest) | `hasAnyRole('ADMIN','FRONT_DESK')` | reads tenant data |
| NotificationController | PATCH | `/api/v1/admin/notifications/{recipientId}/read` | authenticated (anyRequest) | `hasAnyRole('ADMIN','FRONT_DESK')` | mutates |
| NotificationController | PATCH | `/api/v1/admin/notifications/read-all` | authenticated (anyRequest) | `hasAnyRole('ADMIN','FRONT_DESK')` | mutates |
| OrderController | GET | `/api/v1/order/my-orders` | authenticated (anyRequest) | `hasRole('CUSTOMER')` | reads tenant data |
| OrderController | GET | `/api/v1/order/{orderNumber}` | authenticated (anyRequest) | `hasAnyRole('CUSTOMER', 'ADMIN')` | reads tenant data |
| OrderController | POST | `/api/v1/order/{orderNumber}/cancel` | authenticated (anyRequest) | `hasRole('CUSTOMER')` | mutates |
| OrderController | GET | `/api/v1/order/all` | authenticated (anyRequest) | `hasRole('ADMIN')` | reads tenant data |
| OrderController | PUT | `/api/v1/order/update-order-status/{orderNumber}` | authenticated (anyRequest) | `hasRole('ADMIN')` | mutates |
| OrderController | POST | `/api/v1/order/{orderNumber}/refund-acknowledgement` | authenticated (anyRequest) | `hasRole('ADMIN')` | mutates (ORD-01: clears refundRequired, audit row; see [orders.md](orders.md)) |
| OrderController | POST | `/api/v1/order/{orderNumber}/return-received` | authenticated (anyRequest) | `hasRole('ADMIN')` | mutates (ORD-02: restocks a paid order cancelled while out for delivery, audit row; see [orders.md](orders.md)) |
| OrderController | POST | `/api/v1/order/{orderNumber}/return` | authenticated (anyRequest) | `hasRole('ADMIN')` | mutates (ORD-07: returns a whole DELIVERED order: restock, sale reversed, refund required, coupon released, audit row; see [orders.md](orders.md)) |
| OrderController | GET | `/api/v1/order/{orderNumber}/audit-events` | authenticated (anyRequest) | `hasRole('ADMIN')` | reads tenant data (ORD-02: the order's audit trail, paged) |
| PaymentController | GET | `/api/v1/payment/verify` | authenticated (anyRequest) | `hasRole('CUSTOMER')` | reads tenant data |
| PaymentSettingsController | GET | `/api/v1/payment-settings` | authenticated (anyRequest) | `hasRole('ADMIN')` | reads tenant data |
| PaymentSettingsController | PUT | `/api/v1/payment-settings` | authenticated (anyRequest) | `hasRole('ADMIN')` | mutates |
| PaymentSettingsController | POST | `/api/v1/payment-settings/rotate-webhook-token` | authenticated (anyRequest) | `hasRole('ADMIN')` | mutates (API-04: old webhook URL dies at once) |
| TenantCustomDomainController | GET | `/api/v1/admin/custom-domain` | authenticated (anyRequest) | `hasRole('ADMIN')` (class) | reads the shop's own custom domain (DOMAIN-02) |
| TenantCustomDomainController | PUT | `/api/v1/admin/custom-domain` | authenticated (anyRequest) | `hasRole('ADMIN')` (class) | mutates master.tenants (claim, PENDING) |
| TenantCustomDomainController | POST | `/api/v1/admin/custom-domain/verify` | authenticated (anyRequest) | `hasRole('ADMIN')` (class) | mutates (DNS TXT check, rate limited) |
| TenantCustomDomainController | DELETE | `/api/v1/admin/custom-domain` | authenticated (anyRequest) | `hasRole('ADMIN')` (class) | mutates (removes the domain) |
| ProductController | POST | `/api/v1/product/add-product` | ADMIN | `hasRole('ADMIN')` | mutates |
| ProductController | PUT | `/api/v1/product/update/{productId}` | ADMIN | `hasRole('ADMIN')` | mutates |
| ProductController | GET | `/api/v1/product/featured` | permitAll (GET) | - | reads tenant data (public storefront data) |
| ProductController | GET | `/api/v1/product/listing` | permitAll (GET) | - | reads tenant data (public storefront data) |
| ProductController | GET | `/api/v1/product/admin/listing` | ADMIN | `hasRole('ADMIN')` | reads tenant data |
| ProductController | GET | `/api/v1/product/search` | permitAll (GET) | - | reads tenant data (public storefront data) |
| ProductController | GET | `/api/v1/product/admin/search` | ADMIN, FRONT_DESK (GET) | `hasAnyRole('ADMIN','FRONT_DESK')` | reads tenant data |
| ProductController | GET | `/api/v1/product/{slug}` | permitAll (GET) | - | reads tenant data (public storefront data) |
| ProductController | GET | `/api/v1/product/main/{productId}` | ADMIN | `hasRole('ADMIN')` | reads tenant data |
| ProductController | PUT | `/api/v1/product/admin/{productId}/discount` | ADMIN | `hasRole('ADMIN')` | mutates |
| ProductController | DELETE | `/api/v1/product/admin/{productId}/discount` | ADMIN | `hasRole('ADMIN')` | mutates |
| ProductController | DELETE | `/api/v1/product/delete/{productId}` | ADMIN | `hasRole('ADMIN')` | mutates |
| ProductFamilyController | GET | `/api/v1/admin/product-families` | authenticated (anyRequest) | `hasRole('ADMIN')` | reads tenant data |
| ProductFamilyController | GET | `/api/v1/admin/product-families/{id}/available-uoms` | authenticated (anyRequest) | `hasRole('ADMIN')` | reads tenant data |
| SalesReportController | GET | `/api/v1/admin/inventory/sales/items-sold` | authenticated (anyRequest) | `hasRole('ADMIN')` | reads tenant data |
| SalesReportController | GET | `/api/v1/admin/inventory/sales/items-sold/{productId}` | authenticated (anyRequest) | `hasRole('ADMIN')` | reads tenant data |
| ShopWideDiscountController | PUT | `/api/v1/admin/shop-discount` | authenticated (anyRequest) | `hasRole('ADMIN')` | mutates |
| ShopWideDiscountController | GET | `/api/v1/admin/shop-discount` | authenticated (anyRequest) | `hasRole('ADMIN')` | reads tenant data (API-06: discounts in force or scheduled) |
| ShopWideDiscountController | GET | `/api/v1/admin/shop-discount/current` | authenticated (anyRequest) | `hasRole('ADMIN')` | reads tenant data |
| ShopWideDiscountController | DELETE | `/api/v1/admin/shop-discount/{id}` | authenticated (anyRequest) | `hasRole('ADMIN')` | mutates |
| StockConversionController | GET | `/api/v1/admin/stock-conversions/reverse/{sourceProductId}/variants` | authenticated (anyRequest) | `hasAnyRole('ADMIN','FRONT_DESK')` | reads tenant data |
| StockConversionController | POST | `/api/v1/admin/stock-conversions/forward` | authenticated (anyRequest) | `hasAnyRole('ADMIN','FRONT_DESK')` | mutates |
| StockConversionController | POST | `/api/v1/admin/stock-conversions/reverse` | authenticated (anyRequest) | `hasAnyRole('ADMIN','FRONT_DESK')` | mutates |
| StockTransferController | POST | `/api/v1/admin/inventory/transfers` | authenticated (anyRequest) | `hasAnyRole('ADMIN','FRONT_DESK')` | mutates |
| StockTransferController | GET | `/api/v1/admin/inventory/transfers` | authenticated (anyRequest) | `hasAnyRole('ADMIN','FRONT_DESK')` | reads tenant data |
| StorageLocationController | POST | `/api/v1/admin/inventory/locations` | authenticated (anyRequest) | `hasRole('ADMIN')` | mutates |
| StorageLocationController | PUT | `/api/v1/admin/inventory/locations/{id}` | authenticated (anyRequest) | `hasRole('ADMIN')` | mutates |
| StorageLocationController | GET | `/api/v1/admin/inventory/locations` | authenticated (anyRequest) | `hasAnyRole('ADMIN', 'FRONT_DESK')` | reads tenant data |
| StorageLocationController | GET | `/api/v1/admin/inventory/locations/{id}` | authenticated (anyRequest) | `hasAnyRole('ADMIN', 'FRONT_DESK')` | reads tenant data |
| StorageLocationController | PATCH | `/api/v1/admin/inventory/locations/{id}/defaults` | authenticated (anyRequest) | `hasRole('ADMIN')` | mutates |
| StoreThemeController | GET | `/api/v1/theme` | permitAll (GET) | - | reads tenant data |
| StoreThemeController | PUT | `/api/v1/theme` | ADMIN (PUT) | `hasRole('ADMIN')` | mutates |
| TaxController | GET | `/api/v1/tax` | authenticated (anyRequest) | - | reads tax rates (any signed-in user) |
| TaxController | POST | `/api/v1/tax/add-tax` | authenticated (anyRequest) | `hasRole('ADMIN')` | mutates |
| TaxController | PUT | `/api/v1/tax/update/{taxId}` | authenticated (anyRequest) | `hasRole('ADMIN')` | mutates |
| TaxController | GET | `/api/v1/tax/all` | authenticated (anyRequest) | - | reads tax rates (any signed-in user) |
| TaxController | GET | `/api/v1/tax/{taxId}` | authenticated (anyRequest) | `hasRole('ADMIN')` | reads tenant data |
| TaxController | DELETE | `/api/v1/tax/delete/{taxId}` | authenticated (anyRequest) | `hasRole('ADMIN')` | mutates |
| WalkInOrderController | POST | `/api/v1/admin/walk-in/order` | authenticated (anyRequest) | `hasAnyRole('ADMIN', 'FRONT_DESK')` | mutates |
| WalkInOrderController | GET | `/api/v1/admin/walk-in/customers/search` | authenticated (anyRequest) | `hasAnyRole('ADMIN', 'FRONT_DESK')` | reads tenant data |
| WalkInOrderController | GET | `/api/v1/admin/walk-in/orders` | authenticated (anyRequest) | `hasAnyRole('ADMIN', 'FRONT_DESK')` | reads tenant data |
| WalkInOrderController | GET | `/api/v1/admin/walk-in/orders/{orderNumber}` | authenticated (anyRequest) | `hasAnyRole('ADMIN', 'FRONT_DESK')` | reads tenant data |
| WalkInOrderController | PATCH | `/api/v1/admin/walk-in/orders/{orderNumber}/receipt-printed` | authenticated (anyRequest) | `hasAnyRole('ADMIN', 'FRONT_DESK')` | mutates |
| WebhookController | POST | `/api/v1/webhook/{token}` | permitAll (POST) | - | mutates payments; auth = per-tenant token + HMAC |
| PlatformAuthController | POST | `/api/v1/platform/auth/login` | permitAll (explicit POST) | - | mutates |
| PlatformAuthController | POST | `/api/v1/platform/auth/refresh` | permitAll (explicit POST) | - | mutates |
| PlatformAuthController | POST | `/api/v1/platform/auth/logout` | permitAll (explicit POST) | - | mutates |
| PlatformAuthController | GET | `/api/v1/platform/auth/me` | SUPER_ADMIN | `hasRole('SUPER_ADMIN')` | reads tenant data |
| PlatformTenantController | GET | `/api/v1/platform/tenants` | SUPER_ADMIN | `hasRole('SUPER_ADMIN')` (class) | reads tenant data |
| PlatformTenantController | GET | `/api/v1/platform/tenants/{slug}` | SUPER_ADMIN | `hasRole('SUPER_ADMIN')` (class) | reads tenant data |
| PlatformTenantController | GET | `/api/v1/platform/tenants/{slug}/audit` | SUPER_ADMIN | `hasRole('SUPER_ADMIN')` (class) | reads tenant data |
| PlatformTenantController | POST | `/api/v1/platform/tenants/{slug}/approve` | SUPER_ADMIN | `hasRole('SUPER_ADMIN')` (class) | mutates |
| PlatformTenantController | POST | `/api/v1/platform/tenants/{slug}/suspend` | SUPER_ADMIN | `hasRole('SUPER_ADMIN')` (class) | mutates |
| PlatformTenantController | POST | `/api/v1/platform/tenants/{slug}/reactivate` | SUPER_ADMIN | `hasRole('SUPER_ADMIN')` (class) | mutates |
| PlatformTenantController | POST | `/api/v1/platform/tenants/{slug}/reprovision` | SUPER_ADMIN | `hasRole('SUPER_ADMIN')` (class) | mutates |
| PlatformTenantDomainController | GET | `/api/v1/platform/tenants/{slug}/custom-domain` | SUPER_ADMIN | `hasRole('SUPER_ADMIN')` (class) | reads tenant data (DOMAIN-02) |
| PlatformTenantDomainController | POST | `/api/v1/platform/tenants/{slug}/custom-domain/verify` | SUPER_ADMIN | `hasRole('SUPER_ADMIN')` (class) | mutates (re-verification) |
| PlatformTenantDomainController | DELETE | `/api/v1/platform/tenants/{slug}/custom-domain` | SUPER_ADMIN | `hasRole('SUPER_ADMIN')` (class) | mutates (removal) |
| TenantOnboardingController | POST | `/api/v1/platform/onboarding/signup` | permitAll (explicit) | - | mutates |
| TenantOnboardingController | POST | `/api/v1/platform/onboarding/verify` | permitAll (explicit) | - | mutates |
| TenantOnboardingController | GET | `/api/v1/platform/onboarding/status` | permitAll (explicit) | - | reads tenant data |
| TenantOnboardingController | POST | `/api/v1/platform/onboarding/resend` | permitAll (explicit) | - | mutates |

Rows with `@PreAuthorize` `-` and a non-public matcher (any signed-in tenant user, decided in SEC-03b): `AuthController` `GET /auth/me`, `TaxController` `GET /tax` (tax calculation for a subtotal and coupon) and `GET /tax/all` (rate list). **Tax policy:** the storefront frontend calls neither (checkout totals come from `POST /cart/preview`), but rates are not secret (every checkout total shows them), the calculation is the same one a customer already triggers through checkout, and an API client may rely on it, so the routes stay open to signed-in users while the rate administration (`add-tax`, `update`, `{taxId}`, `delete`) is `ADMIN`. Revisit if rates ever become confidential.

## 4. SEC-03b hardening

* Public catalog reads (`/product/{featured,listing,search}`) now return `PublicProductListing`: no `stockQuantity`, `isActive`, `isEnlisted` (availability is `isOutOfStock`). The ADMIN/FRONT_DESK listings keep the full `ProductListing`.
* Public catalog sort is allow-listed (`id`, `name`, `createdAt`, `price.amount`; anything else is `400 INVALID_SORT`) and public search matches the product name only (not the SKU). ADMIN/FRONT_DESK search and sort are unchanged.
* Cache deploy note: the public listing/featured caches were renamed to `publicCustomerProductListings` / `publicFeaturedProducts`. Entries written by older builds under `customerProductListings` / `featuredProducts` are never read and simply expire (rollback to the old build is safe; it reads its own old names). A cached value that is not a `PublicProductListing` is treated as a miss and evicted.
* Swagger/api-docs off in prod; `/actuator/prometheus` token-protected; other actuator paths denied; HEAD follows GET (see section 2).

## 5. Fixed in SEC-03

* `DELETE /api/v1/category/delete/{id}`: no `@PreAuthorize`, permitAll matcher: **anonymous deletion of tenant categories**. Now ADMIN at both levels.
* `/api/v1/category/**` and `/api/v1/product/**` were permitAll for every method; now only the storefront GETs are.
* `AccountingController` (`/api/v1/admin/accounting/**`, income statement, balance sheet, cash flow, journals): no role check at all, **any signed-in CUSTOMER could read the ledger**. Now class-level `hasRole('ADMIN')`.
* `/api/v1/auth/**`, `/api/v1/webhook/**`, `/api/v1/platform/{auth,onboarding}/**`, swagger were permitAll for every method and unknown sub-paths; now method-specific.
* `/api/v1/dev/**` was permitAll in every profile (controller bean only in dev); now `denyAll` unless the dev flag is on.
* Platform controllers carry `@PreAuthorize("hasRole('SUPER_ADMIN')")` in addition to the matcher.

## 6. Residual risks / follow-ups

* `GET /api/v1/tax` and `/tax/all` are readable by any signed-in tenant user (decision above).
* STOMP (fixed in SEC-03c, see 2a): sessions are tenant-bound, anonymous and foreign-tenant CONNECTs are refused, SUBSCRIBE/SEND are default-deny. Remaining: a tenant suspended or a user disabled AFTER a session connected keeps that session until it closes or its token expires (SUBSCRIBE is refused after expiry; already-open subscriptions keep receiving until the socket closes). A refresh token is also accepted as the CONNECT bearer (no token-type claim). Both are follow-up SEC-03e. The refusal reason in the `ERROR` frame is best-effort (see 2a). The frontend does not use STOMP yet.
* The prometheus scrape token is a shared secret in an env var; rotate it by changing the env var and the Prometheus credentials together. In `docker-compose.yml` Prometheus reads it from the untracked file `./prometheus-scrape-token`.
* `GET /product/{slug}` records a view (async event) for every non-admin GET, so a GET has a write side effect (a counter, no authorization impact) and reloads or GET loops can inflate `view_count`. Documented residual: a per-client-IP dedupe on GET was tried in SEC-03c and dropped (review F1), because the storefront renders product pages on the frontend server, so the backend sees the frontend server's IP for every visitor and would collapse counts to about one view per 30 minutes per frontend server. Real counting is follow-up VIEW-01 (a browser-side view beacon). `HEAD /product/{slug}` returns the same response but records neither a view nor a site visit (`SiteVisitFilter` skips HEAD). A scraper misconfigured with a wrong prometheus token produces a 401 per scrape (no ERROR log from the app).
