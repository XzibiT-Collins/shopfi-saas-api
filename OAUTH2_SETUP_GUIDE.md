# OAuth2 with Google - Setup Guide

This guide will help you set up and test Google OAuth2 authentication in your Perfume Budget application.

## 📋 Prerequisites

- Java 21
- PostgreSQL database running
- Redis server running
- Google Cloud account

## 🔧 Setup Steps

### Step 1: Create Google OAuth2 Credentials

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a new project or select an existing one
3. Navigate to **APIs & Services** > **Credentials**
4. Click **Create Credentials** > **OAuth 2.0 Client ID**
5. Configure the OAuth consent screen:
   - User Type: External (for testing) or Internal (for organization)
   - Add required information (App name, user support email, etc.)
   - Add scopes: `email`, `profile`
   - Add test users if using External type
6. Create OAuth 2.0 Client ID:
   - Application type: **Web application**
   - Name: `Perfume Budget`
   - Authorized JavaScript origins:
     - `http://localhost:8080`
     - `http://localhost:3000` (if you have a frontend)
   - Authorized redirect URIs:
     - `http://localhost:8080/oauth2/callback/google`
     - `http://localhost:8080/login/oauth2/code/google`
7. Copy the **Client ID** and **Client Secret**

### Step 2: Update Environment Variables

Update your `.env` file with the credentials from Step 1:

```env
# OAuth2 Google Configuration
GOOGLE_CLIENT_ID=your-actual-client-id.apps.googleusercontent.com
GOOGLE_CLIENT_SECRET=your-actual-client-secret
OAUTH2_REDIRECT_URIS=http://localhost:3000/oauth2/redirect
```

**Note:** Replace `your-actual-client-id` and `your-actual-client-secret` with your actual Google credentials.

### Step 3: Run Database Migration

The OAuth2 columns will be automatically added to your `users` table when you run the application (using Hibernate's `ddl-auto=update`).

Alternatively, you can manually run the migration script:

```sql
-- Run this in your PostgreSQL database
-- File: src/main/resources/db/migration/V001__Add_OAuth2_Support.sql

ALTER TABLE users
ADD COLUMN IF NOT EXISTS auth_provider VARCHAR(20) DEFAULT 'LOCAL',
ADD COLUMN IF NOT EXISTS provider_id VARCHAR(255),
ADD COLUMN IF NOT EXISTS email_verified BOOLEAN DEFAULT FALSE,
ADD COLUMN IF NOT EXISTS image_url VARCHAR(512);

CREATE INDEX IF NOT EXISTS idx_provider_id ON users(auth_provider, provider_id);
CREATE INDEX IF NOT EXISTS idx_email_verified ON users(email_verified);

UPDATE users
SET auth_provider = 'LOCAL',
    email_verified = TRUE
WHERE auth_provider IS NULL;
```

### Step 4: Build and Run the Application

```bash
# Clean and build
./mvnw clean install

# Run the application
./mvnw spring-boot:run
```

## 🧪 Testing OAuth2 Authentication

### Option 1: Using Browser

1. Open your browser and navigate to:
   ```
   http://localhost:8080/oauth2/authorize/google
   ```

2. You'll be redirected to Google's login page

3. Sign in with your Google account

4. Grant permissions to the application

5. After successful authentication, you'll be redirected to your frontend with success status

6. Check your browser cookies - you should see:
   - `accessToken` (HttpOnly, 1 hour expiration)
   - `refreshToken` (HttpOnly, 7 days expiration)

### Option 2: Using Frontend Integration

Add this to your React/Angular/Vue frontend:

```javascript
// Initiate Google OAuth2 Login
const handleGoogleLogin = () => {
  window.location.href = 'http://localhost:8080/oauth2/authorize/google';
};

// Handle OAuth2 Redirect (in your redirect page)
useEffect(() => {
  const urlParams = new URLSearchParams(window.location.search);
  const success = urlParams.get('success');
  const error = urlParams.get('error');

  if (success === 'true') {
    // User is authenticated, cookies are set
    console.log('Authentication successful!');
    navigate('/dashboard');
  } else if (error) {
    console.error('OAuth2 error:', error);
    showErrorMessage(error);
  }
}, []);
```

### Option 3: Using Swagger UI

1. Navigate to: `http://localhost:8080/swagger-ui.html`
2. OAuth2 endpoints should be visible
3. Click on `/oauth2/authorize/google` endpoint
4. Execute the request

## 🔍 Verify Implementation

### Check Database

After a successful OAuth2 login, verify the user was created:

```sql
SELECT id, email, full_name, auth_provider, provider_id, email_verified, image_url
FROM users
WHERE auth_provider = 'GOOGLE';
```

### Check Application Logs

Look for these log messages:

```
INFO  - Registering new OAuth2 user: user@gmail.com
INFO  - OAuth2 authentication successful for user: user@gmail.com
```

### Test API Endpoints

Use the authentication cookies to access protected endpoints:

```bash
# Test with curl (cookies will be automatically included if you use -c and -b flags)
curl -X GET http://localhost:8080/api/protected-endpoint \
  --cookie "accessToken=your-token-here"
```

## 🔐 Security Configuration

### Current Setup

- **Session Management:** Stateless (no server-side sessions)
- **Token Storage:** HttpOnly cookies (prevents XSS attacks)
- **Token Expiration:**
  - Access Token: 1 hour
  - Refresh Token: 7 days
- **CORS:** Controlled by `CORS_ALLOWED_ORIGINS`, `CORS_ALLOWED_METHODS`, and `CORS_ALLOWED_HEADERS`
- **CSRF:** Disabled (appropriate for stateless APIs)

### Production Recommendations

1. **Enable HTTPS:**
   ```properties
   # In application.properties for production
   server.ssl.enabled=true
   server.ssl.key-store=classpath:keystore.p12
   server.ssl.key-store-password=your-password
   server.ssl.key-store-type=PKCS12
   ```

2. **Update Cookie Security:**
   In `OAuth2AuthenticationSuccessHandler.java`, set:
   ```java
   cookie.setSecure(true); // Only send over HTTPS
   ```

3. **Configure CORS for production:**
   ```properties
   # Add your production domains
   CORS_ALLOWED_ORIGINS=https://yourdomain.com,https://www.yourdomain.com
   CORS_ALLOWED_METHODS=GET,POST,PUT,PATCH,DELETE,OPTIONS
   CORS_ALLOWED_HEADERS=Authorization,Content-Type,X-Requested-With
   ```

4. **Add rate limiting** to prevent abuse

5. **Enable security headers** (CSP, HSTS, X-Frame-Options)

## 🔄 Dual Authentication Support

Your application now supports **both** authentication methods:

### Traditional Login (Username/Password)
- Existing users continue to work without any changes
- Password-based authentication is maintained
- Users have `auth_provider = 'LOCAL'`

### Google OAuth2 Login
- New authentication method
- No password required
- Users have `auth_provider = 'GOOGLE'`
- Email is automatically verified

### Account Linking Prevention

The system prevents mixing authentication methods:
- If a user signs up with email/password (LOCAL), they cannot login with Google using the same email
- If a user signs up with Google, they cannot login with email/password
- This prevents security issues and user confusion

To enable account linking in the future, you'll need to implement a separate flow in your user settings.

## 🐛 Troubleshooting

### Issue: "redirect_uri_mismatch" error

**Solution:** Make sure the redirect URI in Google Cloud Console exactly matches:
```
http://localhost:8080/oauth2/callback/google
```

### Issue: Cookies not being set

**Solution:**
1. Check CORS configuration allows credentials
2. Verify frontend and backend are on allowed origins
3. Check browser console for security errors

### Issue: "Email not found from OAuth2 provider"

**Solution:**
1. Ensure you've added the `email` scope in Google Cloud Console
2. Verify the OAuth consent screen includes email scope
3. Try re-authenticating after updating scopes

### Issue: Database connection errors

**Solution:**
1. Verify PostgreSQL is running
2. Check database credentials in `.env`
3. Ensure database exists: `CREATE DATABASE perfume_budget;`

## 📚 Additional Resources

- [Spring Security OAuth2 Documentation](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/core.html)
- [Google OAuth2 Documentation](https://developers.google.com/identity/protocols/oauth2)
- [JWT Best Practices](https://datatracker.ietf.org/doc/html/rfc8725)

## 🎯 Next Steps

Consider implementing:

1. **Refresh Token Rotation:** Enhance security by rotating refresh tokens
2. **Account Linking:** Allow users to link Google account to existing LOCAL account
3. **Multiple OAuth2 Providers:** Add Facebook, GitHub, Microsoft
4. **Email Verification for LOCAL users:** Match OAuth2 email verification
5. **Two-Factor Authentication (2FA):** Additional security layer
6. **User Profile Management:** Allow users to manage connected accounts
7. **OAuth2 State Parameter:** Additional CSRF protection (already handled by Spring Security)

## 📧 Support

If you encounter issues:
1. Check application logs for detailed error messages
2. Verify all environment variables are set correctly
3. Ensure all prerequisites are running (PostgreSQL, Redis)
4. Review the troubleshooting section above
