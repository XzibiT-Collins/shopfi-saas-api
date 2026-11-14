# Authorization matrix (SEC-03)

Generated from the controllers and `SecurityConfig` at the SEC-03 fix. Guarded by `AuthorizationMatrixStructureTest` (every controller mutation and read must have `@PreAuthorize` or an explicit allow-list entry) and `CatalogAuthorizationMatrixIntegrationTest` (real filter chain, five actors).

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
| CUSTOMER / FRONT_DESK without the role | 403 JSON (access denied handler) | normal response |
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
| `/ws/**` | SockJS/STOMP handshake: Origin checked by `TenantOriginHandshakeInterceptor`; CONNECT authenticated by `AuthChannelInterceptor`; only user-destination messages are sent (no broadcast topics) |
| `GET /swagger-ui/**`, `/swagger-ui.html`, `/v3/api-docs/**`, `GET /actuator/health`, `GET /actuator/prometheus` | tooling (see residual risks) |
| `/api/v1/dev/**` | **denyAll** unless `tenant.dev-endpoints.enabled=true` (true only in the dev profile; prod sets it false; the controller bean is also conditional on it) |

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
| AuthController | GET | `/api/v1/auth/me` | authenticated | `hasAnyRole('ADMIN', 'CUSTOMER')` | reads tenant data |
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
| PaymentController | GET | `/api/v1/payment/verify` | authenticated (anyRequest) | `hasRole('CUSTOMER')` | reads tenant data |
| PaymentSettingsController | GET | `/api/v1/payment-settings` | authenticated (anyRequest) | `hasRole('ADMIN')` | reads tenant data |
| PaymentSettingsController | PUT | `/api/v1/payment-settings` | authenticated (anyRequest) | `hasRole('ADMIN')` | mutates |
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
| PlatformTenantController | POST | `/api/v1/platform/tenants/{slug}/approve` | SUPER_ADMIN | `hasRole('SUPER_ADMIN')` (class) | mutates |
| PlatformTenantController | POST | `/api/v1/platform/tenants/{slug}/suspend` | SUPER_ADMIN | `hasRole('SUPER_ADMIN')` (class) | mutates |
| PlatformTenantController | POST | `/api/v1/platform/tenants/{slug}/reactivate` | SUPER_ADMIN | `hasRole('SUPER_ADMIN')` (class) | mutates |
| PlatformTenantController | POST | `/api/v1/platform/tenants/{slug}/reprovision` | SUPER_ADMIN | `hasRole('SUPER_ADMIN')` (class) | mutates |
| TenantOnboardingController | POST | `/api/v1/platform/onboarding/signup` | permitAll (explicit) | - | mutates |
| TenantOnboardingController | POST | `/api/v1/platform/onboarding/verify` | permitAll (explicit) | - | mutates |
| TenantOnboardingController | GET | `/api/v1/platform/onboarding/status` | permitAll (explicit) | - | reads tenant data |
| TenantOnboardingController | POST | `/api/v1/platform/onboarding/resend` | permitAll (explicit) | - | mutates |

Rows with `@PreAuthorize` `-` and a non-public matcher: `TaxController` `GET /tax` and `GET /tax/all` (any signed-in user; needed by the customer checkout, rates are not secret).

## 4. Fixed in SEC-03

* `DELETE /api/v1/category/delete/{id}`: no `@PreAuthorize`, permitAll matcher: **anonymous deletion of tenant categories**. Now ADMIN at both levels.
* `/api/v1/category/**` and `/api/v1/product/**` were permitAll for every method; now only the storefront GETs are.
* `AccountingController` (`/api/v1/admin/accounting/**`, income statement, balance sheet, cash flow, journals): no role check at all, **any signed-in CUSTOMER could read the ledger**. Now class-level `hasRole('ADMIN')`.
* `/api/v1/auth/**`, `/api/v1/webhook/**`, `/api/v1/platform/{auth,onboarding}/**`, swagger were permitAll for every method and unknown sub-paths; now method-specific.
* `/api/v1/dev/**` was permitAll in every profile (controller bean only in dev); now `denyAll` unless the dev flag is on.
* Platform controllers carry `@PreAuthorize("hasRole('SUPER_ADMIN')")` in addition to the matcher.

## 5. Residual risks / follow-ups

* `/actuator/prometheus` and swagger/api-docs are public by existing decision; restrict at the reverse proxy or behind a role (separate ticket).
* `GET /api/v1/tax/all` is readable by any signed-in customer.
* STOMP: `SUBSCRIBE` is not authorised per destination; safe today because only `convertAndSendToUser` is used.
