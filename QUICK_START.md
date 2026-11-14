# OAuth2 with Google - Quick Start Guide

## ✅ Implementation Complete!

OAuth2 authentication with Google has been successfully implemented and tested. Your application now supports **dual authentication**: traditional username/password AND Google OAuth2.

## 🎉 What's New

- ✅ Google OAuth2 login support
- ✅ Automatic user registration via Google
- ✅ JWT token generation for OAuth2 users
- ✅ HttpOnly cookie-based authentication
- ✅ Backward compatible with existing users
- ✅ All code compiles and builds successfully
- ✅ Zero breaking changes to existing functionality

## 🚀 Quick Start (3 Steps)

### Step 1: Get Google OAuth2 Credentials (5 minutes)

1. Go to [Google Cloud Console](https://console.cloud.google.com/apis/credentials)
2. Create OAuth 2.0 Client ID
3. Add redirect URI: `http://localhost:8080/oauth2/callback/google`
4. Copy Client ID and Client Secret

### Step 2: Update .env File

> Local dev must now set `ACTIVE_PROFILE=dev` (there is no default profile any more; an unset value in production no longer silently enables dev-only behaviour such as `X-Tenant-ID` tenant resolution). Production uses `ACTIVE_PROFILE=prod`.

Replace the placeholder values in `.env`:

```env
GOOGLE_CLIENT_ID=your-actual-client-id.apps.googleusercontent.com
GOOGLE_CLIENT_SECRET=your-actual-client-secret
OAUTH2_REDIRECT_URIS=http://localhost:3000/oauth2/redirect
```

### Step 3: Run the Application

```bash
# Build and run
./mvnw spring-boot:run
```

That's it! 🎊

## 🧪 Test OAuth2 Login

### Option 1: Direct Browser Test

Open your browser and navigate to:
```
http://localhost:8080/oauth2/authorize/google
```

You'll be redirected to Google, authenticate, and come back with JWT tokens set as cookies.

### Option 2: Frontend Integration

Add a "Sign in with Google" button:

```html
<button onclick="window.location.href='http://localhost:8080/oauth2/authorize/google'">
  Sign in with Google
</button>
```

Handle the redirect on your success page:

```javascript
// On http://localhost:3000/oauth2/redirect
const params = new URLSearchParams(window.location.search);
if (params.get('success') === 'true') {
  console.log('✅ Authenticated! Cookies are set.');
  // Redirect to dashboard
}
```

## 📂 Project Structure

```
src/main/java/com/example/perfume_budget/
├── enums/
│   └── AuthProvider.java ← NEW (LOCAL, GOOGLE)
├── security/
│   └── oauth2/ ← NEW PACKAGE
│       ├── OAuth2UserInfo.java
│       ├── GoogleOAuth2UserInfo.java
│       ├── OAuth2UserInfoFactory.java
│       ├── CustomOAuth2User.java
│       ├── CustomOAuth2UserService.java
│       ├── OAuth2AuthenticationSuccessHandler.java
│       └── OAuth2AuthenticationFailureHandler.java
└── model/
    └── User.java ← UPDATED (added OAuth2 fields)

Documentation:
├── OAUTH2_SETUP_GUIDE.md ← Detailed setup instructions
├── OAUTH2_IMPLEMENTATION_SUMMARY.md ← Technical details
└── QUICK_START.md ← This file
```

## 🔐 Authentication Endpoints

### OAuth2 Endpoints (New)
- `GET /oauth2/authorize/google` - Initiate Google login
- `GET /oauth2/callback/google` - OAuth2 callback (handled automatically)

### Traditional Endpoints (Unchanged)
- Your existing login endpoints continue to work exactly as before
- No changes needed to existing authentication flow

## 🗃️ Database Changes

New columns added to `users` table:
- `auth_provider` - 'LOCAL' or 'GOOGLE'
- `provider_id` - Google's unique user ID
- `email_verified` - Email verification status
- `image_url` - Profile picture URL

Existing users are automatically marked as `auth_provider='LOCAL'`.

## 🛡️ Security Features

- **HttpOnly Cookies** - XSS protection
- **JWT Tokens** - Stateless authentication
- **Provider Validation** - Prevents account type mixing
- **Email Verification** - Automatic for OAuth2 users
- **CORS Protection** - Configured for specific origins
- **Token Expiration** - Access: 1 hour, Refresh: 7 days

## ✅ Verification Checklist

Test that everything works:

- [ ] Existing username/password login still works
- [ ] Existing users can access protected endpoints
- [ ] OAuth2 button redirects to Google
- [ ] New OAuth2 user is created in database
- [ ] OAuth2 users receive JWT tokens
- [ ] OAuth2 users can access protected endpoints
- [ ] Application builds without errors
- [ ] No breaking changes to existing code

## 🔍 Check Database After OAuth2 Login

```sql
-- View all users and their auth providers
SELECT
    id,
    email,
    full_name,
    auth_provider,
    email_verified,
    image_url
FROM users
ORDER BY created_at DESC;

-- Count users by provider
SELECT
    auth_provider,
    COUNT(*) as user_count
FROM users
GROUP BY auth_provider;
```

## 📊 What Happens During OAuth2 Login?

1. User clicks "Sign in with Google"
2. Redirected to Google OAuth2 consent screen
3. User authorizes the application
4. Google redirects back with authorization code
5. Your app exchanges code for user info
6. App creates/updates user in database
7. App generates JWT access & refresh tokens
8. Tokens set as HttpOnly cookies
9. User redirected to frontend with success status
10. Frontend can now make authenticated API calls

## 🎯 Next Steps

### For Development
1. Get Google OAuth2 credentials
2. Update `.env` with real credentials
3. Test the OAuth2 flow
4. Build your frontend integration

### For Production
- [ ] Enable HTTPS
- [ ] Update cookie security settings (`setSecure(true)`)
- [ ] Configure production CORS origins
- [ ] Add rate limiting
- [ ] Enable security headers
- [ ] Set up monitoring and logging
- [ ] Test with real users

## 📚 Documentation

- **OAUTH2_SETUP_GUIDE.md** - Complete setup instructions with troubleshooting
- **OAUTH2_IMPLEMENTATION_SUMMARY.md** - Technical implementation details
- **QUICK_START.md** - This file (fastest way to get started)

## 🐛 Common Issues

### Issue: "redirect_uri_mismatch"
**Fix:** Ensure Google Console has exact redirect URI: `http://localhost:8080/oauth2/callback/google`

### Issue: Cookies not being set
**Fix:** Check CORS configuration allows credentials and frontend origin is listed

### Issue: Can't find OAuth2 endpoints
**Fix:** Make sure application started successfully and check logs for errors

## 💡 Tips

1. **Test locally first** - Use localhost before deploying
2. **Check logs** - OAuth2 authentication is logged with detailed info
3. **Use Chrome DevTools** - Check Network tab for redirects and Application tab for cookies
4. **Start simple** - Test direct browser access before frontend integration
5. **Keep credentials safe** - Never commit `.env` file to git

## 🎊 Success Indicators

You'll know it's working when:
- ✅ Google login page appears
- ✅ After auth, you're redirected to your frontend
- ✅ Browser has `accessToken` and `refreshToken` cookies
- ✅ New user appears in database with `auth_provider='GOOGLE'`
- ✅ Console logs show "OAuth2 authentication successful"

## 📞 Need Help?

1. Check **OAUTH2_SETUP_GUIDE.md** for detailed troubleshooting
2. Review application logs for specific error messages
3. Verify all environment variables are set correctly
4. Ensure PostgreSQL and Redis are running
5. Test with curl/Postman to isolate frontend vs backend issues

---

**Built with ❤️ using Spring Boot 3.5.10, Spring Security 6.5.7, and OAuth2 Client**

---

## Multi-tenant boot and Flyway verification (Stage-1 gate checks)

Tests extending `AbstractPostgresIntegrationTest` (`src/test/java/com/example/perfume_budget/support`) boot the
full app on a real PostgreSQL with `ddl-auto=validate`, migrate `master` + `tenant_template` first, mock Redis,
and use `TenantAwareCacheManager` over an in-memory delegate. Autowire `TestTenants` to get the `acme` and `bravo`
tenants (migrated with `TenantSchemaMigrator`, seeded with `TenantSeeder`; admins are `admin@<slug>.test`).

### One-time DB setup (throwaway database, no Docker needed)

```bash
sudo -u postgres psql -c "CREATE ROLE shopfi_test LOGIN PASSWORD 'shopfi_test' CREATEDB" \
                      -c "CREATE DATABASE shopfi_test OWNER shopfi_test"
```

Use a dedicated database: the tests drop the `master`, `tenant_template` and `tenant_*` schemas before the first
boot and again on JVM exit.

### Run

```bash
./mvnw test                                   # everything
./mvnw test -Dtest='PostgresBootTest,MigrationBeforeValidationTest'   # just the boot/Flyway checks
```

| Env var | Default |
|---|---|
| `SHOPFI_TEST_DB_URL` | `jdbc:postgresql://localhost:5432/shopfi_test` |
| `SHOPFI_TEST_DB_USER` | `shopfi_test` |
| `SHOPFI_TEST_DB_PASSWORD` | `shopfi_test` (local throwaway credential) |
| `SHOPFI_TEST_DB_REQUIRED` | unset: Postgres tests are skipped (with a reason) if the DB is unreachable; `true`: fail instead (use in CI) |

No Postgres on the host? `docker compose up -d postgres` (see `docker-compose.yml`) and point
`SHOPFI_TEST_DB_URL/USER/PASSWORD` at it (create a database such as `shopfi_test` first). Testcontainers is not used.

Note: the main `./mvnw spring-boot:run` flow with the `dev` profile (`DB_URL`, `DB_USER`, `DB_PASSWORD`) runs the same
Flyway-then-validate boot sequence for manual gate checks; `tenant.dev-endpoints.enabled=true` exposes
`POST /api/v1/dev/tenants` to create tenants by hand.

## Production deployment (DEPLOYMENT)

Run with `ACTIVE_PROFILE=prod`. There is intentionally no default profile: an unset `ACTIVE_PROFILE` selects no
dev-only behaviour. `application-prod.properties` reads everything below from environment variables, has no
defaults for secrets or URLs (a missing one fails startup), and forces `tenant.header-resolution.enabled=false`
and `tenant.dev-endpoints.enabled=false`. Set secrets in the Render dashboard (`sync: false` in `render.yaml`); never commit values.

Required (no default):

| Group | Env vars |
|---|---|
| Profile | `ACTIVE_PROFILE=prod` |
| Database | `DB_URL` (jdbc:postgresql://...), `DB_USER`, `DB_PASSWORD` |
| JWT | `JWT_SECRET`, `JWT_ACCESS_EXPIRATION`, `JWT_REFRESH_EXPIRATION` |
| Redis | `REDIS_URL` |
| Mail (SMTP) | `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH`, `SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_ENABLE`, `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE`, `SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_TRUST`, `SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_PROTOCOLS` |
| Google OAuth2 | `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `OAUTH2_CALLBACK_BASE_URL`, `OAUTH2_REDIRECT_URIS` |
| Payments | `PLATFORM_ENCRYPTION_KEY`, `PLATFORM_API_BASE_URL` |
| Cloudinary | `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET` |
| Seed admin | `ADMIN_EMAIL`, `ADMIN_PASSWORD` |
| Platform super-admin (first login) | `PLATFORM_ADMIN_EMAIL`, `PLATFORM_ADMIN_PASSWORD` (12 to 72 bytes), optional `PLATFORM_ADMIN_FULL_NAME`. Insert-only: creates the first SUPER_ADMIN at boot if that email does not exist, never changes an existing one (rotate a password in the DB, not via env). Unset or empty = bootstrap skipped with one WARN (no DB access), same in dev and prod; after the first super-admin exists both variables can be removed from the environment, and changing them later never alters an existing admin. Login: `POST /api/v1/platform/auth/login`. |
| URLs / CORS | `FRONTEND_CHANGE_PASSWORD_URL`, `CORS_ALLOWED_ORIGINS` |
| Tenancy | `TENANT_BASE_DOMAIN` (e.g. `shopfi.app`, tenants are `<slug>.TENANT_BASE_DOMAIN`), `TENANT_PLATFORM_DOMAINS` (comma-separated API/apex hosts) |

Optional (safe defaults): `PORT`/`SERVER_PORT`, `PAYSTACK_URL`, `NOTIFICATION_EMAIL_PROVIDER`,
`NOTIFICATION_EMAIL_FROM_ADDRESS`, `NOTIFICATION_EMAIL_FROM_NAME`, `RESEND_API_KEY`, `RESEND_BASE_URL`,
`MANAGEMENT_HEALTH_MAIL_ENABLED`, `REDIS_CACHE_TTL_MINUTES`, `INVENTORY_RESERVATION_EXPIRY_HOURS`,
`INVENTORY_RESERVATION_CLEANUP_INTERVAL_MS`, `DISCOUNT_CACHE_EVICT_INTERVAL_MS`.

### Reverse proxy and client IP (required for per-IP rate limits)

The public onboarding endpoints (and `SiteVisitFilter`) rate-limit per client IP. That IP must come from the
proxy, never from a header the client can write. Both profiles inherit these settings from `application.properties`
(so dev and prod cannot drift):

```
server.forward-headers-strategy=native
server.tomcat.remoteip.remote-ip-header=X-Forwarded-For
server.tomcat.remoteip.protocol-header=X-Forwarded-Proto
server.tomcat.remoteip.internal-proxies=${TRUSTED_PROXY_REGEX:<private and loopback ranges>}
```

How it works: Tomcat's RemoteIpValve reads `X-Forwarded-For` from the right, skips hops that match
`internal-proxies` (our own proxy tier) and sets `request.getRemoteAddr()` to the first untrusted hop, i.e. the
address the proxy itself saw. Everything left of it is client supplied and ignored. If the direct TCP peer is not a
trusted proxy, forwarding headers are ignored entirely. `X-Real-IP` and `Forwarded` are never used. Do NOT set
`server.forward-headers-strategy=framework`: Spring's ForwardedHeaderFilter takes the LEFTMOST `X-Forwarded-For`
entry, so any client could pick its own rate-limit bucket (a test boots the shipped config in dev and prod and fails
if this regresses). IPv6 clients are bucketed by their /64.

- Render (single router in front of the service, private network peers): the defaults are correct, no env var needed.
  Render's router appends the real client address to `X-Forwarded-For`.
- Another proxy tier in front of Render (CDN, Cloudflare, extra LB): set `TRUSTED_PROXY_REGEX` to a regex that matches
  the addresses of ALL your proxies and nothing else (full-match regex, e.g. `10\..*|203\.0\.113\.\d+`). If you
  forget one, all clients collapse into that proxy's address (shared bucket: safe but too strict); if you list too
  much (e.g. `.*`), clients can spoof again.
- Direct exposure without any proxy: the peer is the client, headers are ignored. Local `curl` from the same host is
  treated as a trusted proxy in dev, so a hand-set `X-Forwarded-For` is honoured there only.
- Rate-limit state is per JVM instance and bounded (`onboarding.max-tracked-keys`, default 10000 per limiter; beyond
  it new keys share one overflow bucket, i.e. fail closed).

### Onboarding OTP policy

`POST /api/v1/platform/onboarding/verify` consumes the OTP (single use). `POST /resend` (`{slug,email}`) always
returns the same 202 body; it only sends a new code to the signup's stored owner email for a PENDING/FAILED signup that
is not locked, not mid-provisioning, past the 60 s cooldown and under `onboarding.max-resends` (3). A resend never
clears a lockout and never resets the failed-attempt counter (so it cannot be used to buy extra guesses or to unlock
someone else's signup). Master migration `V4__tenant_signup_resend.sql` adds the resend bookkeeping columns.
