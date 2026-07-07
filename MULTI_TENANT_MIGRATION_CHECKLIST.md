# Multi-Tenant Migration — Progress Checklist

Tracks execution of `MULTI_TENANT_MIGRATION_PLAN.md`. Check items as completed. Each stage is a hard gate — do not start the next stage until the gate passes.

Legend: `[ ]` todo · `[~]` in progress · `[x]` done · `[!]` blocked

---

## Stage 1 — Isolation Foundation (hard gate)

### 1a. Tenancy infra (`tenancy/`)
- [ ] `TenantContext` — typed `TenantIdentity{slug, schemaName, scope}` ThreadLocal; `set/get/getOrDefault/clear`; default `tenant_template`
- [ ] `SchemaMultiTenantConnectionProvider` — wrap Hikari `DataSource` via `ObjectProvider<DataSource>`; `getConnection`→`setSchema`; release→reset to `tenant_template` then close
- [ ] `TenantIdentifierResolver implements CurrentTenantIdentifierResolver<String>`; `validateExistingCurrentSessions()=true`
- [ ] `MultiTenancyJpaConfig` — `HibernatePropertiesCustomizer` registering both beans
- [ ] `application-dev.properties`: `hibernate.multiTenancy=SCHEMA`, Hikari `connectionInitSql=SET search_path TO tenant_template`

### 1b. Flyway split + boot ordering
- [ ] `db/migrations/master/V1__create_master_schema.sql` (`tenants`, `platform_admins`, `payment_tenant_routes`)
- [ ] Move existing `db/migrations/V1..V6` → `db/migrations/tenant/` (preserve numbering)
- [ ] `spring.flyway.enabled=false`
- [ ] `TenantSchemaMigrator` — `migrateMaster()`, `migrateTemplate()`, `migrateTenant(schema)`
- [ ] `tenantFlywayInitializer` bean: master → template → each active tenant
- [ ] `EntityManagerFactoryDependsOnPostProcessor("tenantFlywayInitializer")`

### 1c. Tenant registry (`platform/`, master schema)
- [ ] `platform/model/Tenant` (`@Table(schema="master")`) with fields + `AuditingEntityListener`
- [ ] `platform/model/PlatformAdmin` (`@Table(schema="master")`)
- [ ] `platform/enums/` — `TenantStatus`, `TenantPlan`, `PlatformRole(SUPER_ADMIN)`
- [ ] `TenantRepository`, `PlatformAdminRepository`
- [ ] Explicit master service methods; platform ops set scope=PLATFORM (no tenant context)

### 1d. Tenant resolution filter + JWT binding
- [ ] `tenancy/TenantResolutionFilter` — precedence customDomain→subdomain→header(non-prod)→apex=platform→404; runs on every request; `try/finally clear()`; SUSPENDED→423/403
- [ ] `filter/JWTUtil` — `tenant` claim on access + refresh; `extractTenant`
- [ ] `filter/JWTFilter` — token-tenant vs context mismatch → 401; skip for PLATFORM
- [ ] `SecurityConfig` — `tenantResolutionFilter` before `SiteVisitFilter` and `JWTFilter`
- [ ] `AuthServiceImpl.login` + `CustomUserDetailsService` — pass context slug into token gen
- [ ] `AuthCookieUtil` — `HttpOnly=true`, `Secure` outside dev, host-only; `refreshAccessToken` validates refresh-token tenant before `findByEmail`

### 1e. Tenant-aware Google OAuth2
- [ ] Carry tenant slug in OAuth2 `state` on authorization request
- [ ] `CustomOAuth2UserService` + `OAuth2AuthenticationSuccessHandler` — restore `TenantContext` from state before user create/lookup; cookie for tenant host; validate token tenant
- [ ] Reject when tenant unresolvable from state (no default-schema fallback)

### 1f. Cache isolation
- [ ] `tenancy/TenantAwareCacheManager` (`@Primary`) — prefix cache names with slug / `__platform__`
- [ ] `DiscountCacheEvictionTask` — loop active tenants via `TenantRunner`

### 1g. Async + scheduled context propagation
- [ ] `AsyncConfig` — `TaskDecorator` on `taskExecutor` AND `webhookTaskExecutor` (covers 8 `@Async` handlers)
- [ ] `tenancy/TenantRunner.forEachTenant(Consumer)`
- [ ] `ReservedProductCleanupTask` — run inside `TenantRunner` loop

### 1h. Seeder refactor
- [ ] `DataSeeder` → idempotent callable `seedTenantDefaults()` (no `ApplicationRunner`)
- [ ] `ChartOfAccountsSeeder` → callable
- [ ] `FeatureFlagSeeder` → callable
- [ ] `UomSeeder` → callable (drop `CommandLineRunner`)
- [ ] No boot-time seeding of default schema; dev helper seeds test tenants

### 1i. Webhook tenant routing
- [ ] `master.payment_tenant_routes` table + entity/repo
- [ ] Write route in `OrderServiceImpl.createPaymentObject`; update provider ref after Paystack init
- [ ] `WebhookProcessorImpl` — verify signature → look up route in master → set `TenantContext` → dispatch async

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
- [ ] `db/migrations/tenant/V7__create_store_settings.sql` + default row seed
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