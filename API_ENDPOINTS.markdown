# API Endpoints Reference

## 🔐 Authentication Endpoints

### OAuth2 Google Login (New)

#### Initiate Google Login
```
GET /oauth2/authorize/google
```
**Description:** Redirects user to Google OAuth2 consent screen
**Public Access:** Yes
**Response:** HTTP 302 Redirect to Google

**Example:**
```javascript
// Frontend button
window.location.href = 'http://localhost:8080/oauth2/authorize/google';
```

#### OAuth2 Callback (Automatic)
```
GET /oauth2/callback/google
```
**Description:** Google redirects here after authentication (handled automatically by Spring Security)
**Public Access:** Yes
**Response:** HTTP 302 Redirect to frontend with query params

**Success Response:**
```
Redirect: http://localhost:3000/oauth2/redirect?success=true
Cookies Set:
  - accessToken (HttpOnly, 1 hour)
  - refreshToken (HttpOnly, 7 days)
```

**Error Response:**
```
Redirect: http://localhost:3000/oauth2/redirect?error=<error_message>
```

### Traditional Login (Existing - Unchanged)

Your existing authentication endpoints continue to work exactly as before. No changes required.

## 🔓 Public Endpoints

The following endpoints are accessible without authentication:

```
GET /swagger-ui/**          - Swagger UI interface
GET /swagger-ui.html        - Swagger documentation
GET /v3/api-docs/**         - OpenAPI specification
GET /oauth2/**              - All OAuth2 endpoints
GET /login/oauth2/**        - OAuth2 login endpoints
```

## 🔒 Protected Endpoints

All other endpoints require authentication via:
- **JWT Token in Cookie:** `accessToken` cookie (set by OAuth2 or traditional login)
- **OR Authorization Header:** `Bearer <token>` (for API clients)

## 📝 How Authentication Works

### OAuth2 Flow
```
1. User clicks "Sign in with Google"
   ↓
2. GET /oauth2/authorize/google
   ↓
3. Redirect to Google consent screen
   ↓
4. User authorizes application
   ↓
5. Google redirects to /oauth2/callback/google
   ↓
6. Backend processes OAuth2 response
   ↓
7. User created/updated in database
   ↓
8. JWT tokens generated
   ↓
9. Tokens set as HttpOnly cookies
   ↓
10. Redirect to frontend with success=true
```

### Traditional Login Flow (Unchanged)
```
Your existing login flow continues to work as before.
No changes to existing authentication mechanism.
```

## 🍪 Authentication Cookies

After successful authentication (OAuth2 or traditional), these cookies are set:

| Cookie Name | Type | Duration | HttpOnly | Secure | Path |
|------------|------|----------|----------|--------|------|
| accessToken | JWT | 1 hour | ✓ Yes | Dev: No<br>Prod: Yes | / |
| refreshToken | JWT | 7 days | ✓ Yes | Dev: No<br>Prod: Yes | / |

**Security Notes:**
- HttpOnly prevents JavaScript access (XSS protection)
- Secure flag should be enabled in production (HTTPS only)
- SameSite policy helps prevent CSRF attacks

## 🎯 Frontend Integration Examples

### React Example

```jsx
import React, { useEffect, useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';

// Login Page Component
function LoginPage() {
  const handleGoogleLogin = () => {
    window.location.href = 'http://localhost:8080/oauth2/authorize/google';
  };

  return (
    <div>
      <h1>Login</h1>
      <button onClick={handleGoogleLogin}>
        Sign in with Google
      </button>
      {/* Your traditional login form */}
    </div>
  );
}

// OAuth2 Redirect Handler Component
function OAuth2RedirectHandler() {
  const navigate = useNavigate();
  const location = useLocation();
  const [error, setError] = useState(null);

  useEffect(() => {
    const params = new URLSearchParams(location.search);
    const success = params.get('success');
    const errorMsg = params.get('error');

    if (success === 'true') {
      // Authentication successful! Cookies are set.
      console.log('✅ OAuth2 authentication successful');
      navigate('/dashboard');
    } else if (errorMsg) {
      setError(errorMsg);
      console.error('❌ OAuth2 authentication failed:', errorMsg);
      setTimeout(() => navigate('/login'), 3000);
    }
  }, [location, navigate]);

  if (error) {
    return <div>Authentication failed: {error}</div>;
  }

  return <div>Processing authentication...</div>;
}
```

### Vue.js Example

```vue
<template>
  <div>
    <h1>Login</h1>
    <button @click="handleGoogleLogin">
      Sign in with Google
    </button>
  </div>
</template>

<script>
export default {
  methods: {
    handleGoogleLogin() {
      window.location.href = 'http://localhost:8080/oauth2/authorize/google';
    }
  },
  mounted() {
    // Handle OAuth2 redirect
    const urlParams = new URLSearchParams(window.location.search);
    const success = urlParams.get('success');
    const error = urlParams.get('error');

    if (success === 'true') {
      console.log('✅ OAuth2 authentication successful');
      this.$router.push('/dashboard');
    } else if (error) {
      console.error('❌ OAuth2 authentication failed:', error);
      this.$router.push('/login');
    }
  }
}
</script>
```

### Angular Example

```typescript
// login.component.ts
import { Component } from '@angular/core';

@Component({
  selector: 'app-login',
  template: `
    <h1>Login</h1>
    <button (click)="handleGoogleLogin()">
      Sign in with Google
    </button>
  `
})
export class LoginComponent {
  handleGoogleLogin() {
    window.location.href = 'http://localhost:8080/oauth2/authorize/google';
  }
}

// oauth2-redirect.component.ts
import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';

@Component({
  selector: 'app-oauth2-redirect',
  template: '<p>Processing authentication...</p>'
})
export class OAuth2RedirectComponent implements OnInit {
  constructor(
    private route: ActivatedRoute,
    private router: Router
  ) {}

  ngOnInit() {
    this.route.queryParams.subscribe(params => {
      const success = params['success'];
      const error = params['error'];

      if (success === 'true') {
        console.log('✅ OAuth2 authentication successful');
        this.router.navigate(['/dashboard']);
      } else if (error) {
        console.error('❌ OAuth2 authentication failed:', error);
        this.router.navigate(['/login']);
      }
    });
  }
}
```

## 🧪 Testing with cURL

### Test OAuth2 Endpoints

```bash
# This will return HTML redirect page
curl -v http://localhost:8080/oauth2/authorize/google
```

### Test Protected Endpoint with Cookie

```bash
# After authentication, test with cookie
curl -v http://localhost:8080/api/protected-endpoint \
  --cookie "accessToken=your-jwt-token-here"
```

### Test with Authorization Header

```bash
curl -v http://localhost:8080/api/protected-endpoint \
  -H "Authorization: Bearer your-jwt-token-here"
```

## 🔍 Debugging Tips

### Check if OAuth2 is Working

1. **Check Application Logs:**
```
Look for: "OAuth2 authentication successful for user: email@example.com"
```

2. **Check Browser Cookies:**
```
Open DevTools → Application → Cookies
Should see: accessToken and refreshToken
```

3. **Check Database:**
```sql
SELECT * FROM users WHERE auth_provider = 'GOOGLE';
```

4. **Test Redirect:**
```
Direct browser to: http://localhost:8080/oauth2/authorize/google
Should redirect to Google login page
```

## 📊 User Account Types

### LOCAL Users (Traditional)
- Created via traditional registration
- Have password (hashed with BCrypt)
- `auth_provider = 'LOCAL'`
- Must verify email separately (if implemented)

### GOOGLE Users (OAuth2)
- Created via Google OAuth2
- No password (empty string)
- `auth_provider = 'GOOGLE'`
- Email automatically verified
- Profile image URL stored

### Account Type Enforcement
- Users cannot mix authentication methods
- If user exists with LOCAL, cannot login with GOOGLE
- If user exists with GOOGLE, cannot login with LOCAL
- This prevents confusion and security issues

## 🌐 CORS Configuration

Currently configured for:
```
http://localhost:3000
http://localhost:4200
http://localhost:8080
```

To add more origins, update `SecurityConfig.java`:
```java
configuration.setAllowedOrigins(List.of(
    "http://localhost:3000",
    "http://localhost:4200",
    "https://yourdomain.com"  // Add production domain
));
```

## 🚀 Production Considerations

### Update for Production

1. **Enable HTTPS:**
```properties
server.ssl.enabled=true
server.ssl.key-store=classpath:keystore.p12
```

2. **Secure Cookies:**
```java
// In OAuth2AuthenticationSuccessHandler.java
cookie.setSecure(true);  // Only send over HTTPS
```

3. **Update Redirect URIs:**
```env
OAUTH2_REDIRECT_URIS=https://yourdomain.com/oauth2/redirect
```

4. **Update Google Console:**
- Add production redirect URI: `https://yourdomain.com/oauth2/callback/google`
- Add production JavaScript origin: `https://yourdomain.com`

## 📞 Support

For issues or questions:
1. Check application logs for detailed error messages
2. Review `OAUTH2_SETUP_GUIDE.md` for troubleshooting
3. Verify Google Cloud Console configuration
4. Test with browser DevTools Network tab
5. Check database for user creation

---

**API Version:** 0.0.1-SNAPSHOT
**Spring Boot Version:** 3.5.10
**Spring Security Version:** 6.5.7
