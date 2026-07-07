---
name: endpoint-flow
description: Use when creating or refactoring REST endpoints in a layered Spring Boot backend that uses DTOs, service orchestration, response envelopes, validation, pagination, and centralized exception handling.
---

# Endpoint Flow

Use this skill for controller-first API work in a layered Spring Boot service.

## Goals

- Expose predictable, consistent endpoints.
- Keep HTTP concerns in controllers and business rules in services.
- Standardize validation, pagination, authorization, and error handling.

## Workflow

1. Start from the API contract: route, method, request DTO, response DTO, status code.
2. Add controller method with validation and authorization annotations only.
3. Implement or extend the service method that owns the use case.
4. Add repository or query changes only if the service truly needs them.
5. Map entities to response DTOs through mappers, not controllers.
6. Add tests for controller behavior or service behavior depending on current test style.

## Controller Rules

- Keep controllers thin: parse transport input, delegate, wrap result.
- Use a consistent envelope such as `CustomApiResponse<T>`.
- Prefer `Pageable` plus a page response DTO for listings.
- Use `@ModelAttribute` only for multipart or form-based requests.
- Use `@RequestBody` for JSON payloads.
- Apply `@PreAuthorize` at the controller or method level for route access.
- Do not call repositories directly from controllers.
- Do not build entities in controllers.

## Service Boundary

- A controller should call one primary service method for one use case.
- Services own validation that depends on persisted state.
- Services own orchestration across repositories, mappers, events, and external APIs.
- If a use case mutates multiple records, mark the service method `@Transactional`.

## API Design Conventions

- Prefer resource-based names over action-heavy names where practical.
- Keep naming consistent across modules.
- Return `204` for delete or no-payload side effects.
- Return `200` for successful reads and updates unless creation semantics require `201`.
- Support filter params and pagination on list endpoints.
- Normalize route prefixes by bounded context, such as `/api/v1/auth`, `/api/v1/product`, `/api/v1/order`.

## Validation

- Put shape validation on request DTOs.
- Put business validation in services.
- Let the global exception handler translate failures.
- Prefer custom exceptions over generic runtime exceptions.

## Best Practices To Enforce

- Use explicit request and response DTOs for every non-trivial endpoint.
- Keep controller log statements sparse and useful.
- Do not expose entities directly.
- Avoid hidden side effects in GET endpoints.
- For endpoints that trigger async work, return the main business result only and publish events from the service.
- For multipart endpoints, validate file type and size in a dedicated service or utility, not inline.

## Clean Code Standard

- One endpoint method should fit on screen without scrolling when possible.
- Favor descriptive method names: `getMyOrders`, `updateOrderStatus`, `searchProducts`.
- Keep response construction uniform.
- Keep route, auth, validation, and service invocation readable at a glance.
