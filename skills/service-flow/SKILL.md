---
name: service-flow
description: Use when implementing or refactoring Spring Boot service-layer business logic, orchestration, transactions, domain validation, event publishing, external integrations, or multi-repository workflows.
---

# Service Flow

Use this skill for business logic and orchestration in a layered Spring Boot backend.

## Goals

- Make the service layer the single owner of use-case rules.
- Keep transactions correct and small.
- Keep orchestration readable even when multiple collaborators are involved.

## Responsibilities

- load and validate domain state
- enforce ownership, status, and invariants
- call repositories, mappers, utilities, gateways, and event publishers
- decide transaction boundaries
- translate failures into domain-specific exceptions

## Workflow

1. Name the use case clearly before coding.
2. Gather all required collaborators through constructor injection.
3. Validate input-dependent business rules early.
4. Load all required persisted entities.
5. Apply the domain change in a readable order.
6. Persist changes.
7. Publish events or call external systems at the correct point.
8. Return a response DTO or mapped projection.

## Transaction Rules

- Put `@Transactional` on mutating service methods, not controllers.
- Keep non-mutating methods non-transactional unless lazy-loading or consistency requirements justify it.
- Avoid long transactions that include slow remote calls when possible.
- If an external call must happen inside a workflow, decide explicitly whether local state should be saved before or after that call.

## Orchestration Pattern

- Split long methods into private helpers with domain names.
- Keep helper methods side-effect aware and intention revealing.
- Group steps as validate, load, calculate, mutate, persist, notify.
- Prefer a small number of collaborators per method; extract a secondary domain service if orchestration becomes crowded.

## Exception Rules

- Throw domain-specific exceptions with actionable messages.
- Do not return `null` for business failures.
- Use `ResourceNotFoundException`, `BadRequestException`, `DuplicateResourceException`, `PaymentException`, or a dedicated type that fits the domain.

## Best Practices To Enforce

- Never let repositories encode core business logic that belongs in services.
- Avoid passing raw request DTOs deep into helper layers when a smaller domain input is enough.
- Do not publish domain events from controllers.
- Protect invariants before persistence and re-check when concurrent updates are possible.
- Prefer optimistic locking or explicit version checks for hot entities.
- Wrap multi-entity writes that must stay consistent in one transaction.
- For external payments or webhooks, persist enough local state to recover and retry safely.

## Readability Standard

- One public method per use case.
- Private helpers should reduce mental load, not just move lines around.
- Use named constants for repeated error text.
- Keep calculations close to the rule they support.
- Avoid large boolean condition trees; extract predicate helpers.

## Test Expectations

- happy path
- state-based rejection paths
- permission or ownership rejection
- transaction-sensitive behavior
- integration or gateway failure handling
- event publishing where behavior depends on it
