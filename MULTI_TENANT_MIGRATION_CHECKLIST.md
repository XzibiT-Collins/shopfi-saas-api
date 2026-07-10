# Multi-Tenant Migration — Progress Checklist

Tracks execution of `MULTI_TENANT_MIGRATION_PLAN.md`. Check items as completed. Each stage is a hard gate — do not start the next stage until the gate passes.

Legend: `[ ]` todo · `[~]` in progress · `[x]` done · `[!]` blocked

---

## Stage 1 — Isolation Foundation (hard gate)

### 1a. Tenancy infra (`tenancy/`)
- [x] `TenantContext` — typed `TenantIdentity{slug, schemaName, scope}` ThreadLocal; `set/get/getOrDefault/clear`; default `tenant_template` (also `TenantScope`, `TenantIdentity`)
- [x] `SchemaMultiTenantConnectionProvider` — wrap Hikari `DataSource` via `ObjectProvider<DataSource>`; `getConnection`→`setSchema`; release→reset to `tenant_template` then close
- [x] `TenantIdentifierResolver implements CurrentTenantIdentifierResolver<String>`; `validateExistingCurrentSessions()=true`
- [x] `MultiTenancyJpaConfig` — `HibernatePropertiesCustomizer` registering both beans
- [x] `application-dev.properties`: `hibernate.multiTenancy=SCHEMA`, Hikari `connectionInitSql=SET search_path TO tenant_template`
  - Note: boot deferred until 1b creates the `tenant_template` schema. `mvn compile` passes.

### 1b. Flyway split + boot ordering
- [x] `db/migrations/master/V1__create_master_schema.sql` (`tenants`, `platform_admins`, `payment_tenant_routes`)
- [x] Move existing `db/migrations/V1..V6` → `db/migrations/tenant/` (preserve numbering, via `git mv`)
- [x] `spring.flyway.enabled=false`
- [x] `TenantSchemaMigrator` — `migrateMaster()`, `migrateTemplate()`, `migrateTenant(schema)` + `listActiveTenantSchemas()` (JDBC, pre-EMF)
- [x] `tenantFlywayInitializer` bean: master → template → each active tenant
- [x] `EntityManagerFactoryDependsOnPostProcessor("tenantFlywayInitializer")`
  - Note: `mvn compile` passes; full boot verification (needs Postgres/Redis env) deferred to the Stage 1 gate.

### 1c. Tenant registry (`platform/`, master schema)
- [x] `platform/model/Tenant` (`@Table(schema="master")`) with fields + `AuditingEntityListener` (owner_email unique)
- [x] `platform/model/PlatformAdmin` (`@Table(schema="master")`)
- [x] `platform/enums/` — `TenantStatus`, `TenantPlan`, `PlatformRole(SUPER_ADMIN)`
- [x] `TenantRepository` (findBySlug/CustomDomain/SchemaName, existsBySlug/OwnerEmail, findByStatus), `PlatformAdminRepository` (findByEmail, existsByEmail)
- [x] Explicit master service: `TenantRegistryService` + impl (read-only), tenant context not required (master entities schema-qualified)
  - Note: `mvn compile` passes.

### 1d. Tenant resolution filter + JWT binding
- [x] `tenancy/TenantResolutionFilter` — **Origin/Referer-host** based (single API host); customDomain/subdomain match; dev `X-Tenant-ID`; platform path→PLATFORM; infra+webhook bypass; unknown origin→404; no signal→neutral; SUSPENDED→423; `try/finally clear()`; also `TenantResolutionProperties`
- [x] `filter/JWTUtil` — `tenant` claim on access + refresh (all 4 generators); `extractTenant`
- [x] `filter/JWTFilter` — token-tenant vs context mismatch → 401; skip for PLATFORM/neutral
- [x] `SecurityConfig` — `tenantResolutionFilter` before `SiteVisitFilter` and `JWTFilter`
- [x] `AuthServiceImpl.login` + `CustomUserDetailsService` — satisfied without changes: JWTUtil reads slug from `TenantContext` at generation; user lookup already runs in the resolved schema
- [x] `AuthCookieUtil` — `HttpOnly=true`, `Secure`/`SameSite` from config (prod: `None`+`Secure`), no domain; cookie shared on single API host, isolation = JWT claim check; `refreshAccessToken` validates signature + tenant before `findByEmail`
  - Note: `mvn compile` passes; `tenant.*` dev config added (header resolution enabled for dev only). **Revised for single-frontend/single-backend: tenant resolves from Origin/Referer, not backend Host.**

### 1e. Tenant-aware Google OAuth2  (single API host — no OTC)
- [x] Tenant derived from login `redirect_uri` host + validated against registry via `OAuth2RedirectValidator` (single open-redirect gate, re-checked at redirect point in success handler); slug + redirect_uri stored as auth-request attributes
- [x] Carry through `state` via stateless `HttpCookieOAuth2AuthorizationRequestRepository` (authorize+callback both on API host); restore `TenantContext` + stash redirect on `removeAuthorizationRequest`
- [x] `CustomOAuth2UserService` — guard rejects if tenant unresolved (no default-schema fallback)
- [x] `OAuth2AuthenticationSuccessHandler` — set cookies on API host + redirect to validated `redirect_uri`
- [x] Fixed Google `redirect-uri` (Google forbids wildcards); `OAuth2TenantHandoffService`/`OAuth2CompleteController` removed
  - Note: `mvn compile` passes. Simplified after architecture clarification (single frontend + single backend); one-time-code handoff was unnecessary.

### 1f. Cache isolation
- [x] `tenancy/TenantAwareCacheManager` (`@Primary`, wraps `redisCacheManager`) — prefix cache names with slug / `__platform__`; existing `@Cacheable`/`@CacheEvict` untouched
- [x] `DiscountCacheEvictionTask` — loop active tenants via `TenantRunner` (done in 1g)
  - Note: `mvn test-compile` passes; also fixed `SecurityConfigTest` constructor (new tenant/oauth2 args from 1d/1e).

### 1g. Async + scheduled context propagation
- [x] `AsyncConfig` — `TenantContextTaskDecorator` on `taskExecutor` AND `webhookTaskExecutor` (propagates context across the 8 `@Async` handlers)
- [x] `tenancy/TenantRunner.forEachActiveTenant(Runnable)` — per-tenant context, per-tenant failure isolation
- [x] `ReservedProductCleanupTask` + `DiscountCacheEvictionTask` — run inside `TenantRunner` loop
  - Note: `mvn test-compile` passes; `ReservedProductCleanupTaskTest` + `SecurityConfigTest` run green.

### 1h. Seeder refactor
- [x] `DataSeeder` → idempotent `seedAdmin(email,password,fullName)` (no `ApplicationRunner`)
- [x] `ChartOfAccountsSeeder` → `seed()` callable
- [x] `FeatureFlagSeeder` → `seed()` callable
- [x] `UomSeeder` → `seed()` callable (dropped `CommandLineRunner`/`@Order`)
- [x] No boot-time seeding of default schema; `config/TenantSeeder` orchestrates under active `TenantContext`
- [x] Dev trigger: `DevTenantController` `POST /api/v1/dev/tenants` (create+migrate+seed a test tenant), gated by `tenant.dev-endpoints.enabled` (dev only) — TEMPORARY, superseded by Stage 2 onboarding
  - Note: `mvn test-compile` passes.

### 1i. Per-tenant payment gateway + webhook routing (each tenant uses its OWN Paystack keys)
- [x] Master `V2` migration: drop `payment_tenant_routes`; add `tenants.webhook_token` (unique); `Tenant.webhookToken` + `TenantRepository.findByWebhookToken` (+ `TenantRegistryService`)
- [x] Tenant `V7` migration: `payment_gateway_config` + `PaymentGatewayConfig` entity/repo (tenant schema)
- [x] `utils/AesGcmEncryptor` (AES-256-GCM, JDK) + `platform.encryption.key`; Paystack secret encrypted at rest (round-trip test green)
- [x] `TenantPaymentGatewayProvider` + `PaystackGatewayServiceImpl`/`PaystackWebhookServiceImpl` use per-tenant decrypted secret (dropped `@Value` secret); global `paystack.secret` retired
- [x] `WebhookController` → `POST /api/v1/webhook/{token}`: master token→tenant → set context → verify HMAC with tenant secret → dispatch async
- [x] `PaymentSettingsController` (ADMIN) `/api/v1/payment-settings` — save keys + return webhook URL (from `platform.api-base-url`); GET returns `secretConfigured` (never the secret)
  - Note: `mvn test-compile` passes; `AesGcmEncryptorTest` green. `application-test` given platform.* config.

### Stage 1 gate — verification
- [ ] Boot: master + `tenant_template` migrated, `validate` passes
- [ ] 2 test tenants login (password, email-OTP, Google OAuth2) with tenant-bound JWT
- [ ] Public storefront browse resolves tenant from host
- [ ] Product create/list isolated between tenants
- [ ] Replay tenant A cookie on tenant B host → 401
- [ ] Redis keys tenant-prefixed (`redis-cli KEYS '*'`)
- [ ] Both scheduled tasks iterate all tenants
- [ ] Paystack webhook lands in correct schema
- [ ] Native accounting queries isolated across 2 schemas
- [ ] Concurrent cross-tenant load → no data leak

---

## Stage 2 — Provisioning & Onboarding

### 2a. Provisioning (`tenancy/`)
- [ ] `TenantProvisioningService.provision(request)` skeleton
- [ ] Schema-name normalize `[a-z0-9_]` + reject reserved (`public`,`master`,`tenant_template`,`pg_*`)
- [ ] Insert `Tenant` PROVISIONING, commit first
- [ ] `migrateTenant(schema)` (V1..V6)
- [ ] Seed inside schema via refactored seeders (admin user, CoA, feature flags, UoM), `finally clear()`
- [ ] Status → ACTIVE / FAILED
- [ ] Saga: idempotent + `DROP SCHEMA CASCADE` compensation; seeding own `@Transactional`
- [ ] Separate tx boundaries: master registry vs tenant DDL/seed

### 2b. Onboarding (`platform/`)
- [ ] `TenantOnboardingController` @ `/api/v1/platform/onboarding`
- [ ] `POST /signup` (public) — validate slug/uniqueness, create PENDING, send OTP
- [ ] `POST /verify` (public) — verify OTP, PENDING→PROVISIONING, trigger `provision` (`@Async`)
- [ ] `GET /status?slug=` (public) — poll until ACTIVE; return storefront login URL
- [ ] `SecurityConfig` — permitAll `/api/v1/platform/onboarding/**`

### Stage 2 gate — verification
- [ ] signup → verify → provisioned → ACTIVE, no manual DB work
- [ ] PG shows `tenant_<slug>` schema with V1..V6 tables + seeded admin
- [ ] Forced seed failure → `FAILED` status, clean retry/compensation works

---

## Stage 3 — Feature Surface

### 3a. Per-tenant theming
- [ ] `db/migrations/tenant/V8__create_store_settings.sql` + default row seed (V7 taken by payment_gateway_config in 1i)
- [ ] `model/StoreSettings` (no `schema=`; JSON via `@JdbcTypeCode`)
- [ ] `StoreSettingsRepository.findTopByOrderByIdAsc`
- [ ] DTOs `dto/theme/` + `mapper/StoreSettingsMapper`
- [ ] `StoreSettingsService` — `getActiveTheme()` cached (tenant-prefixed), `updateTheme()` + `@CacheEvict`
- [ ] `CloudinaryFileUploadUtil` — branding folder
- [ ] `StoreThemeController` @ `/api/v1/theme` — public GET, ADMIN PUT (multipart)
- [ ] Add `store_settings` seed to provisioning

### 3b. SMS transport + wiring (`notification/`)
- [ ] `SmsTransport` interface
- [ ] Ghana provider impl (Arkesel or Hubtel) + config keys (RestTemplate)
- [ ] Wire as alt OTP channel (extend OtpService event consumers)
- [ ] Wire as transactional notifications (order/payment/status handlers)
- [ ] Per-tenant sender config resolution

### 3c. Customer dashboard = existing surface, tenant-scoped
- [ ] Verify cart / orders / delivery-detail / auth/me / storefront browse / tax resolve tenant from host
- [ ] Isolation integration tests for each customer surface

### 3d. WebSocket tenant binding (`config/ws/`)
- [ ] `WebSocketConfig` handshake — stash tenant in STOMP session attrs
- [ ] `AuthChannelInterceptor` CONNECT — validate token tenant vs handshake; reject mismatch
- [ ] Restore `TenantContext` on inbound messages + `NotificationService` sends
- [ ] `AdminSessionManager` — key by `(tenant, username)`; namespace destinations by tenant

### 3e. Platform super-admin control plane (`platform/`)
- [ ] `PlatformAuthController` @ `/api/v1/platform/auth/login` (apex host, `tenant="__platform__"`)
- [ ] `PlatformTenantController` @ `/api/v1/platform/tenants` — list/approve/suspend/reactivate/reprovision
- [ ] `SecurityConfig` — permitAll `/platform/auth/**`, authenticated `/platform/**`

### 3f. Custom domains + dynamic CORS
- [ ] `setAllowedOriginPatterns` (wildcard subdomains) keeping `allowCredentials(true)`
- [ ] `DynamicTenantCorsConfigurationSource` from approved `Tenant.customDomain` (cacheable)
- [ ] Same dynamic origins for WebSocket

### Stage 3 gate — verification
- [ ] `GET /theme` public defaults → `PUT /theme` (ADMIN) persists color+logo/background to Cloudinary; acme ≠ bravo
- [ ] SMS OTP + order SMS delivered via provider (or stub)
- [ ] WebSocket rejects tenant/token mismatch; admins namespaced by tenant
- [ ] Platform super-admin lists + suspends tenants (suspended → 423/403)
- [ ] Custom-domain CORS honored

---

## Cross-stage test matrix (from review)
- [ ] Resolver precedence: custom domain / subdomain / non-prod header / apex platform / unknown host
- [ ] Header resolver disabled in prod
- [ ] JWT `tenant` claim present on access + refresh
- [ ] JWT tenant mismatch → 401 before loading tenant-local user
- [ ] Refresh token cannot be replayed against another tenant
- [ ] `SiteVisitFilter` writes into resolved tenant schema
- [ ] Seeder services seed only active tenant schema and are retry-safe
- [ ] Boot: master + template migrations complete before JPA validation