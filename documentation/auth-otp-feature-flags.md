# Email OTP And Feature Flag Implementation

## Overview

Audience: engineers and maintainers working on authentication, email verification, admin configuration, and security rollout.

This feature separates registration OTP from login OTP and introduces a general feature-flag system that can enable or disable login OTP independently for admins and customers. Registration OTP remains mandatory. Login OTP is optional and is controlled through admin-managed feature flags.

## Problem And Intent

The application already had a registration OTP flow, but it was mixed into a single generic OTP path and could not support multiple OTP purposes cleanly. Email and password login also had no step-up verification.

This implementation addresses three concerns:

- registration must continue to prove email ownership before an account becomes active
- login can now require a second email OTP step after a valid password
- admins need a reusable, runtime-controlled feature-flag mechanism instead of one-off auth toggles

## Implementation Summary

The main behavior is implemented across:

- `AuthServiceImpl`: registration verification, login challenge initiation, login OTP verification, and resend handling
- `OtpServiceImpl`: purpose-specific OTP storage and challenge-token handling
- `FeatureFlagServiceImpl`: role-scoped feature-flag reads and updates
- `FeatureFlagController`: admin endpoints for reading and updating flags
- `FeatureFlagSeeder`: startup seeding of known feature flags
- `OTPEmailSendHandler`: email delivery for registration OTP and login OTP events

The new auth-related DTOs introduced by this feature are:

- `LoginResponse`
- `LoginOtpVerificationRequest`
- `LoginOtpResendRequest`

The general feature-flag domain added by this feature includes:

- `FeatureFlag`
- `FeatureFlagKey`
- `FeatureAudience`

## Auth Flow Behavior

### Registration

- `POST /api/v1/auth/register` still creates the user through the email and password flow.
- Registration now always sends a registration OTP through `OtpService.sendRegistrationOtp(...)`.
- Registration OTP is required to activate the account.
- `POST /api/v1/auth/verify-otp` verifies the registration OTP, marks the user as `emailVerified=true`, marks the user active, and sets auth cookies.
- `POST /api/v1/auth/resend-otp` resends the registration OTP.

Registration OTP is not controlled by feature flags. It is always required.

### Login

- `POST /api/v1/auth/login` still authenticates the email and password first.
- The user must already be active and have `emailVerified=true`.
- If login OTP is disabled for the user audience, login behaves as before and auth cookies are issued immediately.
- If login OTP is enabled for the user audience:
  - auth cookies are not issued yet
  - a challenge token is generated
  - a login OTP is emailed to the user
  - the response returns `requiresOtp=true`, the `challengeToken`, and the user email

Login OTP completion now uses:

- `POST /api/v1/auth/verify-login-otp`
- `POST /api/v1/auth/resend-login-otp`

`verify-login-otp` checks the challenge token first, validates the OTP bound to that challenge, loads the user, and then sets auth cookies.

## OTP Storage Model

The Redis-backed OTP logic is now purpose-aware.

Registration OTP uses:

- `otp:registration:{email}`

Login OTP uses two keys:

- `otp:login:code:{challengeToken}`
- `otp:login:email:{challengeToken}`

This prevents collisions between registration verification and login verification.

Current login OTP behavior:

- a UUID challenge token is generated per login challenge
- the OTP and the email are both stored against that challenge token
- successful verification deletes both login keys
- resending a login OTP reuses the existing challenge token

The current OTP validity window for registration OTP and login OTP is five minutes.

## Feature Flag Model

This implementation introduces a general feature-flag system rather than a login-OTP-specific toggle.

The persisted feature-flag model stores:

- `featureKey`
- `audience`
- `enabled`

Current audience values:

- `ADMIN`
- `CUSTOMER`

Current feature keys:

- `LOGIN_OTP`

Flags are seeded at startup for every known `(featureKey, audience)` pair and default to `false` when missing or disabled.

For `LOGIN_OTP`, this means:

- login OTP can be enabled for admins only
- login OTP can be enabled for customers only
- login OTP can be enabled for both
- login OTP can be disabled for both

## Admin Feature Flag Endpoints

The admin feature-flag endpoints are:

- `GET /api/v1/admin/feature-flags`
- `GET /api/v1/admin/feature-flags/{featureKey}`
- `PATCH /api/v1/admin/feature-flags/{featureKey}`

The update payload is:

```json
{
  "adminEnabled": true,
  "customerEnabled": false
}
```

These endpoints are protected with `@PreAuthorize("hasRole('ADMIN')")`.

## Response And Contract Changes

`POST /api/v1/auth/login` no longer always returns a fully authenticated payload.

It now returns `LoginResponse`, which contains:

- `requiresOtp`
- `challengeToken`
- `email`
- `auth`

Behavior:

- when `requiresOtp=false`, `auth` is populated and login is complete
- when `requiresOtp=true`, `auth` is `null` and the frontend must continue with the login OTP verification flow

The registration OTP verification contract remains on `/verify-otp`, but it is now explicitly backed by registration-only logic in the service layer.

## Rollout Notes

Recommended rollout sequence:

1. deploy the backend with the new feature-flag and OTP changes
2. ensure the `feature_flags` table is present if the environment does not auto-manage schema changes
3. leave `LOGIN_OTP` disabled for both audiences initially
4. update the frontend to handle `LoginResponse.requiresOtp`
5. enable `LOGIN_OTP` for customers and admins as needed through the admin feature-flag endpoint

Because login behavior now depends on the feature flag, the frontend must treat login as a two-state result instead of assuming cookie issuance on every successful password submission.

## Limitations And Current Constraints

- Registration OTP is always required and cannot be disabled through feature flags.
- Login OTP currently applies only to the email and password flow.
- Login OTP is audience-scoped by role only. There is no per-user targeting or percentage rollout yet.
- The system currently supports the known enum-backed feature keys only.
- Missing flags default to `false` through the feature-flag service.

## Testing And Verification

Automated verification completed with:

```bash
mvn test
```

The updated and added test coverage includes:

- `AuthServiceImplTest`
- `OtpServiceImplTest`
- `FeatureFlagServiceImplTest`

Behavior verified by tests includes:

- normal login when `LOGIN_OTP` is disabled
- login challenge response when `LOGIN_OTP` is enabled
- rejection of unverified users during login
- successful registration OTP verification
- successful login OTP verification
- resend behavior for registration OTP and login OTP
- role-scoped feature-flag resolution and updates
- challenge-token OTP storage and cleanup

## Future Extensions

The current feature-flag design is intentionally small and reusable. Future features can add new enum-backed keys without changing the underlying table or endpoint shape.

Likely extensions if needed later:

- more feature keys
- audit metadata on feature-flag changes
- per-environment or per-user rollout
- rate limiting or retry limits for login OTP challenges
