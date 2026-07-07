---
name: auth-flow
description: Use when adding or changing authentication, authorization, OTP, password reset, JWT, cookies, OAuth2 login, or current-user access patterns in a layered Spring Boot backend.
---

# Auth Flow

Use this skill for authentication and authorization work in a Spring Boot backend with DTO, service, repository, and security layers.

## Goals

- Keep controllers transport-only.
- Keep identity, token, and account-state rules in services and security components.
- Centralize cookie and token handling.
- Enforce least privilege and explicit failure handling.

## Architecture

- `controller`: accept validated request DTOs, delegate, wrap response.
- `service`: normalize input, enforce account-state rules, orchestrate OTP/password/token flows.
- `security`: configure route access, filters, OAuth2 handlers, authentication providers.
- `utils`: token or cookie helpers only. Do not hide business rules here.
- `repository`: user lookup and persistence only.

## Workflow

1. Define request and response DTOs first. Use records for transport contracts.
2. Add or update controller endpoints with `@Valid`, minimal branching, and no security logic beyond annotations.
3. Put business rules in the service:
   - normalize email and user-supplied identity fields
   - validate account state
   - verify OTP or reset token
   - issue or revoke tokens through a dedicated helper
4. Update `SecurityConfig` and method-level authorization only after the service behavior is clear.
5. Cover success, rejection, and edge cases with unit tests.

## Rules

- Normalize emails with `strip().toLowerCase()` once at service boundaries.
- Do not generate tokens in controllers.
- Do not query the security context directly from controllers.
- Prefer `BadRequestException`, `UnauthorizedException`, `ForbiddenException`, `InactiveAccountException`, or a dedicated auth exception over raw `IllegalArgumentException`.
- Keep refresh, logout, forgot-password, and verify flows idempotent where possible.
- Never leak whether an email exists unless the product explicitly requires it.
- Gate access with both route security and service-level ownership checks for sensitive data.
- Keep auth-related transactions short and explicit.

## Best Practices To Enforce

- Use `httpOnly(true)` for auth cookies unless there is a deliberate, documented frontend need otherwise.
- Set cookie `secure`, `sameSite`, `path`, and expiry from config, not hardcoded assumptions.
- Separate access-token and refresh-token validation rules.
- Store provider-specific OAuth2 mapping in dedicated classes, not in controllers or services.
- Log security events with identifiers, never secrets, tokens, OTPs, or passwords.
- Prefer explicit exception types over generic `BadCredentialsException` outside the authentication provider boundary.

## Controller Pattern

- Request mapping under a single auth prefix such as `/api/v1/auth`.
- Input validation on DTOs, not hand-rolled null checks in controllers.
- Return a consistent response envelope.
- Use `204 No Content` for pure side-effect endpoints when no payload is needed.

## Service Pattern

- Authenticate credentials through Spring Security primitives.
- Load the canonical user entity before issuing tokens.
- Reject inactive, unverified, locked, or provider-mismatched accounts explicitly.
- Delegate token and cookie mechanics to one helper component.
- Keep helper methods private and intention-revealing: `getActiveUserByEmail`, `checkDuplicateRegistration`, `passwordsMatch`.

## Test Expectations

- login success and invalid-credential failure
- registration duplicate-email and password-mismatch failure
- OTP verification success and expired or invalid token failure
- refresh and logout behavior
- forgot-password privacy behavior
- role and ownership access behavior when applicable

## Clean Code Standard

- Controllers stay thin.
- Service methods do one auth use case each.
- Helper names describe the rule being enforced.
- Avoid boolean flag arguments unless they represent a stable domain rule.
- Prefer small private methods over long linear auth methods.
