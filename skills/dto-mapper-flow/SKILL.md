---
name: dto-mapper-flow
description: Use when defining request or response DTOs, page responses, projections, or mapper classes in a Spring Boot backend that keeps entities separate from API contracts.
---

# DTO And Mapper Flow

Use this skill for transport contracts and mapping boundaries.

## Goals

- Keep entities out of API responses.
- Make request and response shapes explicit.
- Centralize mapping so controllers and services stay readable.

## DTO Rules

- Use Java records for request and response DTOs by default.
- Put bean validation annotations on request DTO fields.
- Keep response DTOs presentation-oriented, not persistence-oriented.
- Create dedicated DTOs per endpoint family when shapes diverge.
- Use page wrapper DTOs for paginated results.

## Mapper Rules

- Keep mapping in mapper classes, not controllers.
- Prefer stateless static mappers unless MapStruct or dependency-backed mapping is already standard.
- Mapper names should reflect the aggregate: `ProductMapper`, `OrderMapper`, `UserMapper`.
- Mappers may compose nested response DTOs, but should not fetch from repositories.
- Null handling must be deliberate and consistent.

## Workflow

1. Define request DTO from the API contract.
2. Define response DTO from caller needs, not from entity shape.
3. Add or update mapper methods for entity-to-response and request-to-entity conversion.
4. Keep service logic free of field-by-field response assembly when a mapper is appropriate.

## Best Practices To Enforce

- Avoid leaking internal IDs, audit fields, or security-sensitive fields unless required.
- Prefer formatted fields only in response DTOs, not entities.
- Keep derived booleans in mapping when they are presentation concerns, such as `isOutOfStock`.
- If mapping logic becomes complex, extract a named helper rather than growing one giant method.
- Preserve currency, timezone, and enum semantics explicitly.

## Clean Code Standard

- Mapper methods should read as transformations, not mini-services.
- DTO names must communicate scope clearly: `ProductListing`, `ProductDetails`, `OrderListResponse`.
- No repository calls, no security-context access, no side effects in mappers.
