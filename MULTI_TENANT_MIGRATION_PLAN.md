# Shopfi: Single-Tenant → Multi-Tenant SaaS Migration (reconsidered, staged)

> This document supersedes the original big-bang plan. It merges `MULTI_TENANT_MIGRATION_PLAN_REVIEW.md`, corrects stale facts found by re-reading the code, adopts the review's staged rollout, and folds in the auth/SMS decisions. Progress is tracked in `MULTI_TENANT_MIGRATION_CHECKLIST.md`.

## Context

`shopfi-backend` is a working single-shop e-commerce API (Spring Boot 3.5.10, Java 21, PostgreSQL, Spring Data JPA / Hibernate 6, Flyway 11, JWT-in-cookies, Redis cache, Cloudinary, Paystack, WebSocket). All data lives in one schema; queries scope only by `User` where relevant. There is **no** tenant/store notion, no `StoreSettings`/theming, and **no existing multi-tenant infrastructure** (greenfield — grep for `Tenant|MultiTenant|CurrentTenantIdentifier` returns nothing).

Goal: turn it into a multi-tenant SaaS where many shops operate in **data isolation via separate PostgreSQL schemas (schema-per-tenant)**. Schema isolation is chosen so existing JPA query/filter logic stays untouched (no tenant FK column on every table). A `master` schema holds the tenant registry + platform admins + webhook routing. Shops self-onboard and customize storefront branding (colors, logo, background, fonts) via API. Each tenant is reached by custom domain / subdomain / (dev-only) header, and the storefront + customer dashboard render tenant-specifically off that resolution.

## Decisions locked (with user)

1. **Customer auth is NOT rebuilt.** Existing flows stay: email+password, email-OTP (registration verify + optional feature-flagged login-OTP), Google OAuth2. Email remains the login identifier. `User.password` stays `NOT NULL`. No phone-login, no unique-phone column. Customers remain `User` rows with `role=CUSTOMER` — schema-per-tenant isolates them naturally (each tenant has its own `users` table; the same email/phone can exist in different tenants).
2. **All auth becomes tenant-aware, same treatment for every role** (customer + admin + FRONT_DESK) — JWT `tenant` claim, tenant-scoped user lookup, and **tenant-aware Google OAuth2** (not deferred).
3. **SMS is a new capability, not a new identity.** Build an abstract `SmsTransport` + one Ghana provider (Arkesel or Hubtel), then wire it as (a) an alternate OTP delivery channel alongside email and (b) transactional customer notifications (order/payment/status). Lands in Stage 3.
4. **Staged ship:** Stage 1 Isolation Foundation → Stage 2 Provisioning & Onboarding → Stage 3 Feature Surface. Each stage is a hard gate.
5. **Customer dashboard stays the same feature set, just tenant-specific** — no new domains (no wishlist). Existing customer surfaces (`/order/my-orders`, `/order/{orderNumber}`, `/delivery-detail`, `/auth/me`, cart, storefront browse) simply resolve their tenant from the host. No new aggregate dashboard endpoint.
6. **Routing precedence:** customDomain → subdomain (`<slug>.platform.com`) → `X-Tenant-ID` header (**non-prod only**) → apex/api host = platform-scoped → else 404.
7. **Billing out of scope** — only a `plan` enum on `Tenant`.

## Corrections to the original plan (verified against code)

- Migrations are **V1..V6**, not V1..V4. Move all six into the tenant template. (`V1 initial 32 tables`, `V2 alloc index`, `V3 discounts`, `V4 order auto-discount`, `V5 storeroom inventory`, `V6 stock transfer sale context`.)
- Flyway location is `classpath:db/migrations` (**plural**). All DDL uses **unqualified** table names → portable across schemas (favorable). One native-query repo (`JournalEntryRepository`, 2 methods) also unqualified → fine.
- Use a dedicated **`tenant_template`** schema as the neutral/validation target for `ddl-auto=validate` — clearer intent than reusing `public`. `TenantContext` default = `tenant_template`; connections reset to it on release.
- **Typed context**: distinguish tenant **slug** (in JWT + host) from **schemaName** (`tenant_<slug>`, what Hibernate needs). Use a small typed context object, never pass `acme` where `tenant_acme` is required.
- Webhook routing needs a **master route table**, not reference-scanning.
- Cookie: `AuthCookieUtil.addCookie` currently sets `httpOnly(false)`. Harden to `HttpOnly=true`, `Secure` outside dev, **host-only (no parent-domain cookie)** so each tenant host isolates its session; pair with the JWT tenant-mismatch check.
- No Hikari pool tuning exists today (fully defaulted, single shared pool) — correct baseline for one-pool schema switching; add `connectionInitSql`/reset hardening only.
- `AdminSessionManager` is in-memory single-node — WebSocket presence must be keyed by tenant; multi-instance shared presence is explicitly **out of scope** (flag it).

## Architecture

- Hibernate `MultiTenancy=SCHEMA`: custom `MultiTenantConnectionProvider<String>` + `CurrentTenantIdentifierResolver<String>`, wired via `HibernatePropertiesCustomizer` (hook the autoconfigured EMF; do not hand-build it).
- Per-checkout `connection.setSchema(schemaName)` (PG driver → `SET search_path`); **reset to `tenant_template` on release** (data-exposure defense).
- `TenantContext` ThreadLocal holding a typed `{slug, schemaName, scope}` where `scope ∈ {TENANT, PLATFORM}`. Set earliest in the filter chain, cleared in `finally`.
- Tenant identity in JWT `tenant` claim (slug) for authenticated requests; resolved from host/header **pre-auth** at login (solves login chicken-and-egg).
- Master schema entities are **schema-qualified** (`@Table(schema="master")`) on the **same EMF** — they resolve regardless of search_path. Separate master EMF is a fallback only if this misbehaves.
- Flyway runs **programmatically**, split locations: `db/migrations/master`, `db/migrations/tenant` (the moved V1..V6 + new). Run at boot for master + template + all active tenants, and at runtime provisioning. `EntityManagerFactoryDependsOnPostProcessor` forces EMF to wait for migration (boot-order defense).

New packages:
- `com.example.perfume_budget.tenancy` — context, providers, resolver, JPA config, migrator, provisioning, runner, cache manager, task decorator.
- `com.example.perfume_budget.platform` — master-scoped: `Tenant`/`PlatformAdmin` entities, repos, enums, onboarding + platform-admin controllers/services, `payment_tenant_routes`.

## Risk register (address explicitly)

1. **Login chicken-and-egg** — resolve tenant from host/header before auth, bake slug into JWT. (Stage 1)
2. **HikariCP schema leak** — reset connection schema to `tenant_template` on release, `connectionInitSql=SET search_path TO tenant_template`, never set a Hikari single `schema` prop. (Stage 1)
3. **`ddl-auto=validate` boot order** — migrate master + `tenant_template` before EMF builds; EMF depends-on the migration bean. (Stage 1)
4. **No tenant context off-thread** — `TaskDecorator` on both async executors; scheduled tasks loop tenants via `TenantRunner`. (Stage 1)
5. **Cross-tenant cache bleed** — tenant-prefixed cache names + tenant-looped scheduled eviction; explicit platform namespace (no silent `public` fallback). (Stage 1)
6. **Header-trust in prod** — `X-Tenant-ID` gated to `tenant.header-resolution.enabled` (false in prod). (Stage 1)
7. **Webhook has no tenant discriminator** — master route table written at payment init, read before async processing. (Stage 1)
8. **OAuth2 wrong-schema user creation** — tenant carried in OAuth2 `state`, restored on callback, validated into token. (Stage 1)
9. **Schema-name SQL injection** — normalize to `[a-z0-9_]`, reject reserved (`public`, `master`, `tenant_template`, `pg_*`); name is interpolated into DDL/search_path. (Stage 2)
10. **DDL auto-commit vs JPA tx** — `CREATE SCHEMA`/Flyway are outside the JPA tx (PG auto-commits DDL) → provisioning is a saga with status state + `DROP SCHEMA CASCADE` compensation + idempotent seeders. (Stage 2)

---

## Stage 1 — Isolation Foundation (hard gate)

Deliver a **tenant-safe version of the existing API** before any new SaaS feature. No onboarding UI, no theming yet; tenants created by a dev/admin helper for testing.

### 1a. Tenancy infra (`tenancy/`)
- `TenantContext` — ThreadLocal of typed `TenantIdentity{slug, schemaName, scope}`; `set/get/getOrDefault/clear`; default = `tenant_template`.
- `SchemaMultiTenantConnectionProvider implements MultiTenantConnectionProvider<String>` — wrap autoconfigured Hikari `DataSource` via `ObjectProvider<DataSource>` (dodge circular init). `getConnection(schema)`→`c.setSchema(schema)`; `releaseConnection`→`c.setSchema("tenant_template")` **then** close; `getAnyConnection`/`releaseAnyConnection`→neutral.
- `TenantIdentifierResolver implements CurrentTenantIdentifierResolver<String>` → returns current schemaName or `tenant_template`; `validateExistingCurrentSessions()=true`.
- `MultiTenancyJpaConfig` — `HibernatePropertiesCustomizer` registering both beans. `application-dev.properties`: `spring.jpa.properties.hibernate.multiTenancy=SCHEMA`; Hikari `connectionInitSql=SET search_path TO tenant_template`.

### 1b. Flyway split + boot ordering (`tenancy/`)
- New `db/migrations/master/V1__create_master_schema.sql` — `master.tenants`, `master.platform_admins`, `master.payment_tenant_routes`.
- **Move** existing `db/migrations/V1..V6` → `db/migrations/tenant/` (preserve numbering; this is the per-tenant template).
- `spring.flyway.enabled=false` (programmatic control).
- `TenantSchemaMigrator` — `migrateMaster()` (schemas `master`, location `.../master`); `migrateTemplate()` (schemas/default `tenant_template`, location `.../tenant`); `migrateTenant(schema)` (creates schema if missing, own `flyway_schema_history`, location `.../tenant`).
- `tenantFlywayInitializer` bean: migrate master → migrate `tenant_template` (validation target) → read `master.tenants` ACTIVE → migrate each. `EntityManagerFactoryDependsOnPostProcessor("tenantFlywayInitializer")`.

### 1c. Tenant registry (`platform/`, master schema)
- `platform/model/Tenant` — `@Table(name="tenants", schema="master")`: `id, slug(unique, ^[a-z0-9-]{3,40}$), displayName, schemaName(unique, tenant_<slug>), status(PENDING/PROVISIONING/ACTIVE/SUSPENDED/FAILED), plan(FREE/STARTER/PRO), ownerEmail, customDomain(nullable unique), createdAt/updatedAt`. Reuse `AuditingEntityListener`.
- `platform/model/PlatformAdmin` — `@Table(schema="master")`: `id, email(unique), password(bcrypt), fullName, isActive`.
- `platform/enums/` — `TenantStatus`, `TenantPlan`, `PlatformRole(SUPER_ADMIN)` (kept OUT of `UserRole`).
- `platform/repository/TenantRepository` (`findBySlug`, `findByCustomDomain`, `existsBySlug`, `findByStatus`), `PlatformAdminRepository`.
- Explicit master service methods; platform operations never set a tenant context (scope=PLATFORM).

### 1d. Tenant resolution filter + JWT binding
- `tenancy/TenantResolutionFilter` (`OncePerRequestFilter`, runs on **every** request incl. permitAll storefront). Precedence per Decision 6. `ACTIVE`→set context; `SUSPENDED`→423/403; whitelist `/api/v1/platform/**` as PLATFORM scope; unknown tenant host→404. Wrap chain in `try/finally { TenantContext.clear(); }`.
- `filter/JWTUtil` — add `tenant` (slug) claim to access + refresh tokens; add `extractTenant`.
- `filter/JWTFilter` — after extracting claims, compare token `tenant` vs resolved context slug; **mismatch → 401** (core cross-tenant defense). Skip when PLATFORM-scoped.
- `security/SecurityConfig` — register `tenantResolutionFilter` **before** `SiteVisitFilter` and `JWTFilter` (SiteVisitFilter writes DB pre-auth, must hit the right schema). Keep static dev CORS for now.
- `service/AuthServiceImpl.login` + `security/CustomUserDetailsService` — tenant already in context from filter; pass context slug into token generation so issued JWT carries it.
- `utils/AuthCookieUtil` — set `HttpOnly=true`, `Secure` outside dev, **host-only** (no domain attr); `refreshAccessToken` must **validate refresh-token tenant before `findByEmail`** (else refresh queries wrong schema).

### 1e. Tenant-aware Google OAuth2 (active path — must be done here)
- Carry tenant **slug in the OAuth2 `state`** on the authorization request (initiated from the tenant host).
- `CustomOAuth2UserService` + `OAuth2AuthenticationSuccessHandler` — restore `TenantContext` from `state` before user create/lookup; issue cookie for the tenant host (redirect back to tenant host); validate resulting JWT `tenant` matches.
- If tenant cannot be resolved from state → reject (never fall back to a default schema).

### 1f. Cache isolation
- `tenancy/TenantAwareCacheManager implements CacheManager` (register `@Primary`) — `getCache(name)`→`delegate.getCache(prefix + ":" + name)` where prefix = current slug or `__platform__`. Existing `@Cacheable`/`@CacheEvict` annotations unchanged (`customerProductListings`, `featuredProducts`, `productDetailsPage`).
- `task/DiscountCacheEvictionTask` — loop all ACTIVE tenants via `TenantRunner`, evict per-tenant prefixes (else only the default cache clears).

### 1g. Async + scheduled context propagation
- `config/AsyncConfig` — add `TaskDecorator` to **both** `taskExecutor` and `webhookTaskExecutor` capturing/restoring `TenantContext` (covers 8 `@Async` handlers: webhook, successful-order, shop-floor-stock, view-count, OTP-email, order-status, forgot-password).
- `tenancy/TenantRunner.forEachTenant(Consumer)` — loop `master.tenants` ACTIVE, set/clear context per tenant.
- `task/ReservedProductCleanupTask` — run inside `TenantRunner` loop.

### 1h. Seeder refactor (hard dependency of provisioning)
- Convert `config/DataSeeder`, `ChartOfAccountsSeeder`, `FeatureFlagSeeder` (`ApplicationRunner`) and `UomSeeder` (`CommandLineRunner`) from boot-time global runners into **idempotent callable services** (`seedTenantDefaults()`), invoked only under an explicit tenant context. No boot-time seeding of a default schema. (In Stage 1 a dev helper calls them to create test tenants; provisioning calls them in Stage 2.)

### 1i. Webhook tenant routing
- `master.payment_tenant_routes(system_reference PK, provider_reference, tenant_slug, schema_name, created_at)`.
- Write the route in `OrderServiceImpl.createPaymentObject` (where `systemReference` is generated) and update `provider_reference` after Paystack init (`OrderServiceImpl` ~line 150), inside a master transaction.
- `WebhookController`/`WebhookProcessorImpl` — verify Paystack signature, parse reference, **look up route in master → set `TenantContext` → then dispatch** the existing `@Async` processing (which now finds the correct schema via TaskDecorator). Source of truth is the master table (do not trust payload for tenant).

### Stage 1 gate
Two tenants (created via dev helper) can: authenticate (password, email-OTP, Google OAuth2) with tenant-bound JWT; browse public storefront (tenant resolved from host); create/list products with zero cross-tenant reads; serve cached endpoints with tenant-prefixed keys; run both scheduled tasks across all tenants; process a Paystack webhook to the correct schema. Replaying tenant A's cookie against tenant B host → 401. Boot succeeds with `validate` against `tenant_template`. Native accounting queries isolated across the two schemas.

---

## Stage 2 — Provisioning & Onboarding

### 2a. Provisioning (`tenancy/`)
- `TenantProvisioningService.provision(request)`:
  - Validate/normalize schema name `[a-z0-9_]`, reject reserved (`public`, `master`, `tenant_template`, `pg_*`) — SQL-injection critical.
  - Insert `Tenant` (status `PROVISIONING`), commit first (visible/retryable).
  - `migrateTenant(schema)` (V1..V6 into new schema).
  - Seed inside schema: `TenantContext.set(schema)` → call refactored seeders (admin `User` from `DataSeeder` logic, chart of accounts, feature flags, UoM). `finally { clear(); }`.
  - Status → `ACTIVE` (or `FAILED`). Saga: `CREATE SCHEMA` + Flyway are outside JPA tx (PG auto-commits DDL) → idempotent + `DROP SCHEMA CASCADE` compensation; seeding step its own `@Transactional`; status is saga state.
- Separate transaction boundaries for master registry writes vs tenant schema DDL/seed.

### 2b. Onboarding (`platform/`, PLATFORM scope, no tenant context)
- `platform/controller/TenantOnboardingController` @ `/api/v1/platform/onboarding`:
  - `POST /signup` (public) — `TenantSignupRequest(slug, displayName, ownerEmail, ownerFullName, password, plan)`; validate slug + uniqueness; create `Tenant` PENDING; send OTP via existing `OtpService`. → 201.
  - `POST /verify` (public) — verify OTP, PENDING→PROVISIONING, trigger `provision` (run `@Async` for large seeds).
  - `GET /status?slug=` (public) — poll until ACTIVE.
  - On ACTIVE → return storefront login URL; owner logs in via existing tenant-scoped `/api/v1/auth/login` (cookie host = tenant host). Never mint a tenant session on the apex host.
- `SecurityConfig`: `.requestMatchers("/api/v1/platform/onboarding/**").permitAll()`.

### Stage 2 gate
A tenant goes signup → OTP verify → provisioned → ACTIVE with schema, all V1..V6 tables, and seeded admin `User`, **without manual DB work**. Failed provisioning leaves a `FAILED` status and is safely retryable/cleanable.

---

## Stage 3 — Feature Surface (depends on stable tenancy)

### 3a. Per-tenant theming (`store_settings`, tenant schema)
- `db/migrations/tenant/V7__create_store_settings.sql` — singleton row per schema: `store_name, accent_color VARCHAR(7), secondary_color VARCHAR(7), background_image_url, logo_url, font_family, currency VARCHAR(3), contact_email/phone/address, social_links JSONB, extra_config JSONB, timestamps`. Seed one default row (add to provisioning seeders).
- `model/StoreSettings` — `@Table(name="store_settings")` (no `schema=`, follows search_path). JSON via `@JdbcTypeCode(SqlTypes.JSON)`. `StoreSettingsRepository.findTopByOrderByIdAsc`.
- DTOs `dto/theme/`, `mapper/StoreSettingsMapper` (static, private ctor like `ProductMapper`).
- `service/StoreSettingsService` — `getActiveTheme()` `@Cacheable("storeTheme")` (tenant-prefixed via TenantAwareCacheManager); `updateTheme()` uploads images via `CloudinaryFileUploadUtil` (add branding folder), `@CacheEvict`.
- `controller/StoreThemeController` @ `/api/v1/theme` — `GET` public (storefront, tenant from host); `PUT` `@PreAuthorize("hasRole('ADMIN')")`, multipart `@ModelAttribute` (mirror `ProductController.createProduct`).

### 3b. SMS transport + wiring (`notification/`)
- `notification/SmsTransport` interface + one Ghana provider impl (**Arkesel or Hubtel**) + config keys (mirror the existing email `SmtpMailTransport`/event pattern). RestTemplate-based.
- Wire as **alt OTP channel**: extend `OtpService` event consumers so registration/login/reset OTP can also dispatch via SMS to the user's phone (from `Profile.phoneNumber`/`DeliveryDetail`) when present/configured. Identity stays email.
- Wire as **transactional customer notifications**: SMS on order confirmation / payment success / status update, alongside existing email handlers (`SuccessfulOrderHandler`, `OrderStatusHandler`).
- Per-tenant sender config in `store_settings.extra_config` or tenant-scoped props; provider credentials platform-level.

### 3c. Customer dashboard = existing surface, tenant-scoped
- No new endpoints/entities. Verify each customer surface resolves tenant purely from host and returns isolated data: cart (`/cart`, `/cart/items`), orders (`/order/my-orders`, `/order/{orderNumber}`), addresses (`/delivery-detail`), `/auth/me`, storefront browse (`/product`, `/category`), pricing (`/tax`), plus the new public `GET /theme` for branding. Add isolation integration tests.

### 3d. WebSocket tenant binding (`config/ws/`)
- `WebSocketConfig` handshake — stash tenant (slug/schema) from token/host into STOMP session attrs.
- `AuthChannelInterceptor` CONNECT — validate token `tenant` vs handshake tenant; reject mismatch.
- Restore `TenantContext` on inbound messages and `NotificationService` sends.
- `AdminSessionManager` — key connected admins by `(tenant, username)`; namespace user destinations by tenant. (Multi-instance shared presence out of scope — documented.)

### 3e. Platform super-admin control plane (`platform/`)
- `platform/controller/PlatformAuthController` @ `/api/v1/platform/auth/login` — `PlatformAdminRepository`-backed, served on apex host; tokens carry `tenant="__platform__"`; `JWTFilter` skips tenant-match for PLATFORM scope.
- `platform/controller/PlatformTenantController` @ `/api/v1/platform/tenants`, `@PreAuthorize("hasRole('SUPER_ADMIN')")`: paged list/filter, `approve`, `suspend`, `reactivate`, `reprovision`.
- `SecurityConfig`: `.requestMatchers("/api/v1/platform/auth/**").permitAll()`, `.requestMatchers("/api/v1/platform/**").authenticated()`.

### 3f. Custom domains + dynamic CORS
- Replace static `setAllowedOrigins` with `setAllowedOriginPatterns(List.of("https://*.platform.com", ...envOrigins))` (keeps `allowCredentials(true)` legal).
- `DynamicTenantCorsConfigurationSource` reflecting approved `Tenant.customDomain` origins (cacheable). Same for WebSocket allowed origins.

### Stage 3 gate
Tenant user APIs, public storefront + theme APIs, platform APIs, realtime notifications, SMS OTP + notifications, and custom-domain CORS all resolve tenant/platform context consistently; acme's theme ≠ bravo's; SMS delivered via the abstract transport.

---

## Critical files to modify

- `security/SecurityConfig.java` — filter order (tenant resolution first), permitAll for onboarding/theme/platform, dynamic CORS (Stage 3).
- `filter/JWTUtil.java` + `filter/JWTFilter.java` — `tenant` claim + mismatch check.
- `service/AuthServiceImpl.java`, `security/CustomUserDetailsService.java`, `utils/AuthCookieUtil.java` — pre-auth tenant, cookie hardening, refresh tenant validation.
- `security/oauth2/CustomOAuth2UserService.java`, `OAuth2AuthenticationSuccessHandler.java` — tenant via OAuth2 state.
- `config/RedisConfig.java` — tenant-aware cache manager.
- `config/AsyncConfig.java` — TaskDecorator on both executors.
- `config/{DataSeeder,ChartOfAccountsSeeder,FeatureFlagSeeder,UomSeeder}.java` — per-tenant callable seeders.
- `task/{ReservedProductCleanupTask,DiscountCacheEvictionTask}.java` — loop tenants.
- `service/OrderServiceImpl.java`, `controller/WebhookController.java`, `service/WebhookProcessorImpl.java` — payment route table.
- `config/ws/{WebSocketConfig,AuthChannelInterceptor,AdminSessionManager}.java`, `notification/NotificationService.java` — WebSocket tenant binding (Stage 3).
- `utils/CloudinaryFileUploadUtil.java` — branding folder (Stage 3).
- `application-dev.properties` — `hibernate.multiTenancy=SCHEMA`, `spring.flyway.enabled=false`, Hikari `connectionInitSql`, `tenant.header-resolution.enabled`, SMS provider keys.
- Migrations: new `db/migrations/master/V1`, move existing `V1..V6` → `db/migrations/tenant/`, new `db/migrations/tenant/V7__create_store_settings.sql`.

## Reuse (don't rebuild)

- `OtpService` (Redis-backed, generic) for onboarding + as SMS-channel base.
- Existing email event pattern (`OtpServiceImpl` publishes events → `EmailServiceImpl`/`SmtpMailTransport`) as the template for `SmsTransport`.
- `CustomApiResponse` + `PageResponse` wrappers, `AuditingEntityListener`, `CloudinaryFileUploadUtil`.
- Existing seeder content (admin user, chart of accounts, feature flags, UoM) invoked per-tenant.
- `ProductController.createProduct` as the multipart `@ModelAttribute` convention for theme update.
- `PaymentReferenceGenerator` (`TNX-...`) — the `systemReference` becomes the master route key.

## Open items / assumptions

- Platform apex domain not defined in code — set `platform.com` placeholder in config.
- Arkesel vs Hubtel choice (both Ghana) deferred to Stage 3 implementation; interface makes it swappable.
- Paystack per-reference metadata not relied upon — master route table is source of truth.
- Multi-instance WebSocket presence (shared registry) out of scope.
- No production data migration — fresh start; current shop re-onboards as tenant #1.