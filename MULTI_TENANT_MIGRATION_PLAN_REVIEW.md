# Review: Multi-Tenant Migration Plan

## Overall Assessment

The plan in `MULTI_TENANT_MIGRATION_PLAN.md` identifies the right core risks for a schema-per-tenant SaaS migration: tenant resolution before authentication, Hikari search path leakage, Flyway boot ordering, async context loss, Redis cache bleed, and production header trust. Those risks are real in this codebase.

The main issue is scope and a few under-specified integration seams. The plan combines foundational isolation, provisioning, onboarding, theming, platform administration, custom domains, OAuth2, WebSocket, scheduled jobs, and Paystack webhooks into one migration. That is technically possible, but it creates a high-risk first release because some existing flows need tenant identity before they can even query the correct schema.

Recommended direction: keep schema-per-tenant, but split the work into staged releases. First make the current API tenant-safe for password auth, caching, migrations, seeders, tasks, and webhooks. Then add onboarding/provisioning. Then add theming, custom domains, platform admin depth, and OAuth2.

## Findings

### High: Paystack webhook tenant routing is not decision-complete

The plan correctly flags that webhooks have no cookie and no request tenant context, but the proposed solution is too vague for the current code.

Current evidence:

- `WebhookController` accepts a platform-wide `POST /api/v1/webhook` without tenant information.
- `WebhookProcessorImpl.processWebhook` is `@Async("webhookTaskExecutor")` and queries `PaymentRepository.findByProviderPaymentReference(reference)` before any tenant can be known.
- `Payment` is tenant-local today and has only `systemReference` and `providerPaymentReference`; there is no master lookup table or tenant field.

Why this matters:

With schema-per-tenant, `findByProviderPaymentReference` can only search one schema at a time. If the webhook arrives without a resolved tenant, the app either queries the wrong schema, loops all tenants, or misses the payment.

Proposed alternatives:

1. Add a master-schema `payment_tenant_routes` table keyed by provider/system reference. Write it during payment initialization with `tenant_slug` and `schema_name`. On webhook receipt, verify Paystack signature, parse the reference, look up the tenant route in master, set `TenantContext`, then dispatch processing.
2. Encode the tenant slug into the generated system reference and send it through Paystack metadata/reference where reliable. Still prefer a master route table as the source of truth because provider payload behavior can change and references are security-sensitive.
3. Use per-tenant webhook URLs only if Paystack supports the operational model you want. This is simpler at runtime but more work to manage for each tenant.

Recommendation: use the master route table. It avoids scanning tenant schemas and gives deterministic async routing.

### High: OAuth2 tenant binding is not sufficiently covered

The plan discusses password login, but the codebase has an active OAuth2 login path.

Current evidence:

- `SecurityConfig` permits `/oauth2/**` and `/login/oauth2/**`.
- `CustomOAuth2UserService` creates or updates `User` records during OAuth2 login.
- `OAuth2AuthenticationSuccessHandler` issues auth cookies through `AuthCookieUtil`.

Why this matters:

OAuth2 callbacks return to platform endpoints that may not preserve tenant host context automatically. If tenant context is absent during `CustomOAuth2UserService`, a customer can be created in the wrong schema or the login can fail against the wrong schema.

Proposed alternatives:

1. Defer OAuth2 for the first SaaS migration release. Disable or hide OAuth2 endpoints until tenant-aware OAuth state is implemented.
2. Make OAuth2 tenant-aware by requiring the tenant on the authorization request, storing tenant slug/schema in OAuth2 state or authorized-client context, restoring `TenantContext` on callback, and validating that the resulting token includes the same tenant.

Recommendation: defer OAuth2 for v1 unless social login is a launch requirement.

### High: Auth cookie security should be fixed during tenant-token work

The plan adds tenant claims to JWTs, but does not call out an existing cookie security issue.

Current evidence:

- `AuthCookieUtil.addCookie` sets `.httpOnly(false)` even though comments say auth tokens are HttpOnly cookies.
- `AuthCookieUtil` does not set a domain, which is safer for tenant host isolation, but this should be made explicit when moving to subdomains/custom domains.

Why this matters:

The SaaS migration increases the impact of token theft or token replay across tenant hosts. Tenant claims reduce cross-tenant replay only after the token is stolen; HttpOnly reduces exposure to script access.

Proposed alternatives:

1. Set auth cookies to `HttpOnly=true`, `Secure=true` outside local dev, path `/`, and host-only by default.
2. Avoid broad parent-domain cookies unless there is a strict need for cross-subdomain sessions. If parent-domain cookies are used, the JWT tenant mismatch check becomes mandatory on every authenticated request.
3. Do not mint tenant user sessions from the apex/platform host. Keep platform-admin cookies separate if platform admin auth is added.

Recommendation: include cookie hardening in the same phase as JWT tenant claims.

### High: One cache-manager wrapper is not enough without all-tenant eviction semantics

The plan proposes a tenant-aware `CacheManager`, which is the right direction, but scheduled eviction behavior needs to be explicit.

Current evidence:

- `ProductCatalogCacheService` has tenant-sensitive `@Cacheable` entries for `featuredProducts` and `productDetailsPage`.
- Several product, inventory, and discount services evict `customerProductListings`, `featuredProducts`, and `productDetailsPage`.
- `DiscountCacheEvictionTask` clears cache names from a scheduled task with no tenant context.

Why this matters:

If `getCache(name)` maps to `TenantContext + ":" + name`, scheduled jobs running with default/no tenant will only clear the default tenant cache. Active tenant caches can retain stale discount/product data until TTL expiry.

Proposed alternatives:

1. Keep the tenant-aware cache wrapper, but make scheduled eviction loop all active tenants through `TenantRunner`.
2. For global scheduled eviction, add a method that clears all cache names matching all active tenant prefixes.
3. For platform/master operations, use an explicit platform cache namespace instead of falling back silently to `public`.

Recommendation: combine tenant-prefixed cache names with tenant-looped scheduled eviction tests.

### Medium: Migration scope is too broad for one safe release

The plan covers infrastructure, data model, platform admin, onboarding, theme settings, CORS, WebSocket, async, scheduled jobs, OAuth2, and webhooks.

Why this matters:

The migration touches authentication, database connection state, startup migrations, cache keys, payment processing, and background processing. These are shared blast-radius areas. A single large implementation will make failures hard to isolate.

Proposed alternative rollout:

1. Phase A - tenant isolation foundation: `TenantContext`, resolver filter, Hibernate schema tenancy, Flyway split, JWT tenant claim, cache prefixing, seed refactor, async/task propagation, and webhook route table.
2. Phase B - tenant provisioning: master tenant registry, schema creation/migration, owner admin seeding, status transitions, and onboarding OTP.
3. Phase C - SaaS feature surface: theme settings API, custom domains/CORS, platform admin management, WebSocket tenant binding, and optional OAuth2 tenant support.

Recommendation: treat Phase A as the first hard gate. Do not add theming or platform admin breadth until existing API isolation is proven.

### Medium: Flyway and `ddl-auto=validate` need a concrete validation schema

The plan correctly disables automatic Flyway and moves to programmatic migrations, but the validation target should be explicit.

Current evidence:

- `application-dev.properties` currently has `spring.jpa.hibernate.ddl-auto=validate`.
- Flyway currently runs from `classpath:db/migrations`.
- The existing migrations create unqualified tables, which is compatible with per-schema migration if Flyway sets the target schema correctly.

Why this matters:

Hibernate validation happens during EntityManagerFactory startup. If the default schema is empty or only contains master tables, validation fails. If `public` is used as an accidental tenant template, it can become a hidden dependency.

Proposed alternatives:

1. Create and migrate a dedicated template schema, for example `tenant_template`, before JPA validation. Point validation/default neutral schema there.
2. Keep `public` as the template schema only if documented as a non-tenant schema and never used for request traffic.
3. Disable validation temporarily only for a transition build, not as the target architecture.

Recommendation: use a dedicated template schema. It communicates intent better than `public`.

### Medium: Existing seeders must stop being boot-time global runners

The plan says to refactor seeders into per-tenant callable seeders, which is necessary and should be treated as a hard dependency of provisioning.

Current evidence:

- `DataSeeder`, `ChartOfAccountsSeeder`, and `FeatureFlagSeeder` implement `ApplicationRunner`.
- `UomSeeder` implements `CommandLineRunner`.
- These classes write tenant-local tables such as `users`, `ledger_accounts`, `feature_flags`, and `units_of_measure`.

Why this matters:

After schema multi-tenancy is enabled, boot-time runners execute under whatever default tenant/schema is active. That can seed the template schema, `public`, or the wrong tenant.

Proposed alternatives:

1. Convert each seeder into an idempotent service method, for example `seedTenantDefaults()`, and call it only from tenant provisioning while `TenantContext` is set.
2. Keep optional local/dev boot seeding behind an explicit property and tenant slug, never as a default runner.
3. Make seed operations independently idempotent so failed provisioning can retry safely.

Recommendation: remove runner behavior for tenant-local seed data before enabling schema tenancy.

### Medium: WebSocket tenant context needs restoration beyond handshake

The plan mentions WebSocket context, but the current implementation has enough moving parts that this should be more explicit.

Current evidence:

- `WebSocketConfig` extracts only `accessToken` into handshake attributes.
- `AuthChannelInterceptor` authenticates only on STOMP `CONNECT`.
- `NotificationService` persists notifications and sends to admin users by email.
- `AdminSessionManager` tracks connected admins globally rather than per tenant.

Why this matters:

With repeated email addresses allowed across tenant schemas, user-specific WebSocket destinations and connected-admin tracking can collide unless tenant identity is part of the session key and message handling context.

Proposed alternatives:

1. Store tenant slug/schema from the token or host in STOMP session attributes during handshake/connect.
2. Validate token tenant against handshake tenant on `CONNECT`.
3. Restore `TenantContext` for inbound messages and notification persistence.
4. Key connected admins by `(tenant, email)` or namespace WebSocket user destinations by tenant.

Recommendation: include WebSocket tenant identity in the same hardening phase as async and notifications, or temporarily disable tenant-sensitive realtime notifications.

### Medium: Master and tenant repositories need explicit transaction/context boundaries

The plan introduces `Tenant` and `PlatformAdmin` entities in `schema="master"` while also enabling schema-based Hibernate multi-tenancy.

Why this matters:

Master queries should not depend on the request tenant search path. Tenant provisioning will switch between master writes and tenant schema migration/seeding. If boundaries are implicit, bugs will be difficult to diagnose.

Proposed alternatives:

1. Keep master entities schema-qualified, as the plan says, but add explicit service methods for master operations and avoid setting tenant context for platform endpoints.
2. Consider a separate master `EntityManagerFactory` only if schema-qualified master entities behave poorly with Hibernate schema multi-tenancy. This adds complexity, so it should be a fallback rather than the first choice.
3. Use separate transaction boundaries for tenant registry state changes and tenant schema seeding.

Recommendation: start with schema-qualified master entities, but add integration tests for master queries while a tenant context is present and absent.

### Low: Native queries appear mostly compatible with schema-per-tenant

Current evidence:

- Native accounting queries in `JournalEntryRepository` use unqualified table names such as `journal_entries`, `journal_entry_lines`, and `ledger_accounts`.

Why this matters:

Unqualified native queries are compatible with schema-per-tenant as long as `search_path` is correct. They are also sensitive to connection schema leakage.

Recommendation: add integration coverage for native accounting queries under two tenant schemas. No plan change is needed unless tests reveal qualified table names elsewhere.

### Low: CORS and allowed origins need dynamic behavior, but should not block core isolation

The plan proposes dynamic tenant CORS and custom-domain lookup. This is useful, but it is not required to prove schema isolation.

Current evidence:

- `SecurityConfig` uses `setAllowedOrigins(...)` with configured values and `allowCredentials(true)`.
- `WebSocketConfig` also uses configured allowed origins.

Recommendation: keep static dev CORS for the foundation phase. Add custom-domain dynamic CORS in the custom-domain phase after tenant registry and domain verification are stable.

## Proposed Alternative Migration Strategy

### Stage 1: Isolation Foundation

Deliver a tenant-safe version of the existing API before adding new SaaS product features.

Include:

- `TenantContext` and request tenant resolution with production-disabled header fallback.
- Hibernate schema multi-tenancy with connection schema reset on release.
- Programmatic Flyway for master plus tenant/template schemas.
- JWT tenant claim generation and mismatch rejection.
- Password login and refresh-token tenant binding.
- Tenant-aware Redis cache naming and tenant-looped scheduled eviction.
- Refactored seeders invoked only under provisioning/explicit tenant context.
- Async `TaskDecorator` for tenant context propagation.
- Master webhook route table for Paystack reference-to-tenant lookup.

Gate:

- Two tenants can authenticate, create/list products, use cached product endpoints, run scheduled tasks, and process payment webhooks without cross-tenant reads or writes.

### Stage 2: Provisioning and Onboarding

Add tenant registry and self-service onboarding after the foundation is proven.

Include:

- `master.tenants` and tenant status lifecycle.
- Tenant signup, OTP verification, schema provisioning, migrations, owner admin seeding, and retryable failure state.
- Idempotent provisioning and clear handling for failed schema creation/seeding.

Gate:

- A tenant can be created from signup through active status without manual database work.

### Stage 3: SaaS Feature Surface

Add features that depend on stable tenancy.

Include:

- Store theme/settings API.
- Platform admin auth and tenant management.
- Custom domains and dynamic CORS.
- WebSocket tenant namespace hardening.
- OAuth2 tenant-aware login, if still required.

Gate:

- Tenant user APIs, public storefront APIs, platform APIs, realtime notifications, and custom domains all resolve tenant/platform context consistently.

## Codebase-Specific Notes

- `SecurityConfig` filter order must change so tenant resolution runs before `SiteVisitFilter` and `JWTFilter`; this matches the plan and is required because `SiteVisitFilter` writes to the database before auth.
- `AuthCookieUtil.refreshAccessToken` must validate the refresh token tenant before loading the user, otherwise refresh can query the wrong tenant schema.
- Existing public product/category endpoints are tenant-sensitive even though they are `permitAll`; tenant resolution must run for public storefront routes too.
- `@Async` event handlers such as email/notification/view-count handlers need review, not only `WebhookProcessorImpl`.
- Platform endpoints should be explicitly platform-scoped and should not silently use `public` as a tenant fallback.
- Tenant identifiers should distinguish slug from schema name. JWTs and hosts should carry slug; Hibernate `TenantContext` should carry schema name or use a typed context object to avoid accidentally passing `acme` where `tenant_acme` is required.

## Recommended Tests

- Tenant resolver precedence: custom domain, subdomain, non-prod header, apex platform host, and unknown host.
- Header resolver disabled in prod.
- JWT tenant claim is present on access and refresh tokens.
- JWT tenant mismatch returns 401 before loading tenant-local user data.
- Refresh token cannot be replayed against another tenant.
- Public storefront routes resolve tenant context without authentication.
- `SiteVisitFilter` writes into the resolved tenant schema.
- Redis cache keys or cache names are tenant-qualified.
- Scheduled discount cache eviction clears all active tenant caches.
- Seeder services seed only the active tenant schema and are retry-safe.
- Paystack webhook reference resolves tenant through master metadata before async processing.
- Native accounting queries return isolated results in two tenant schemas.
- WebSocket connection rejects token/tenant mismatch and namespaces connected admins by tenant.
- Boot starts with master and template/tenant migrations completed before JPA validation.

## Assumptions and Unknowns

- The frontend can route tenant storefront requests by subdomain or custom domain. Exact platform domain is not defined in the codebase.
- Paystack metadata/reference capabilities should be confirmed before choosing the final webhook route payload, but the backend should still keep a master reference route table.
- Billing is intentionally out of scope except for a plan enum on `Tenant`.
- Existing production data is not being migrated; the plan assumes a fresh start.
- No changes were made to `MULTI_TENANT_MIGRATION_PLAN.md`.
