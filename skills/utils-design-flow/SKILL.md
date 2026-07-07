---
name: utils-design-flow
description: Use when creating or refactoring utility helpers, generators, pagination helpers, auth helpers, file helpers, or other shared support code in a Spring Boot backend while preserving clean boundaries.
---

# Utils Design Flow

Use this skill for shared helpers that do not own business workflows.

## Goals

- Keep utility classes narrow, deterministic, and easy to test.
- Prevent utilities from turning into hidden service layers.
- Make shared helpers safe for reuse across modules.

## When A Utility Is Appropriate

- pure formatting or normalization
- ID, slug, code, or reference generation
- pagination wrapper creation
- cookie or token assembly
- file upload helper logic

If the code needs repositories, multi-step domain rules, or transaction ownership, it is probably a service, not a utility.

## Rules

- Prefer stateless helpers.
- Use `final` utility classes with private constructors for static-only helpers.
- Use `@Component` only when configuration or injected collaborators are required.
- Keep method inputs explicit and small.
- Fail fast with domain-appropriate exceptions.
- Make output deterministic unless randomness is the point of the helper.

## Best Practices To Enforce

- Keep utilities free of repository lookups unless the helper is explicitly an infrastructure adapter.
- Separate pure functions from side-effecting helpers such as file upload clients.
- For generators, document uniqueness guarantees and collision behavior.
- For pagination helpers, keep page-number conventions explicit.
- For auth helpers, centralize cookie and token creation, but keep account-state validation in services.
- For file helpers, validate content type, size, and upload failure handling.

## Readability Standard

- Utility names should reveal purpose: `SlugGenerator`, `PaginationUtil`, `AuthCookieUtil`.
- One helper should solve one class of problem.
- Avoid "Utils" classes with unrelated methods.
- Keep regex or formatting rules readable and well named.

## Test Expectations

- pure success cases
- malformed input or edge cases
- collision or uniqueness behavior for generators
- pagination numbering semantics
- exception behavior for side-effecting helpers
