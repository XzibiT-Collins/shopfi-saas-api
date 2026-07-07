---
name: model-design-flow
description: Use when designing or refactoring JPA entities, embeddables, enums, aggregate relationships, persistence constraints, auditing, or domain invariants for a Spring Boot backend.
---

# Model Design Flow

Use this skill for persistence model design in a JPA-based backend.

## Goals

- Model domain invariants explicitly.
- Keep entities persistence-focused and behavior-safe.
- Make relationships, defaults, and constraints obvious.

## Workflow

1. Define the aggregate boundary and ownership rules first.
2. Choose entity, embeddable, enum, or projection based on domain meaning.
3. Add table constraints, indexes, and relationship mappings.
4. Add auditing and concurrency control where needed.
5. Verify that service-layer workflows can enforce invariants without awkward workarounds.

## Entity Rules

- Use `@Entity` only for persisted aggregates or children that need identity.
- Use `@Embeddable` for true value objects such as money or address fragments.
- Add `@Version` for entities with meaningful concurrent updates.
- Prefer explicit `@Table` indexes and uniqueness constraints for lookup-critical fields.
- Default values should be safe and unsurprising.
- Keep entity fields cohesive; move formatting concerns to DTOs or mappers.

## Relationship Rules

- Be explicit about owning side, cascade, and orphan removal.
- Default to `LAZY` for associations unless eager loading is justified.
- Avoid bidirectional relationships unless both directions are needed.
- Keep collection initialization safe to prevent `null` collections.

## Invariant Rules

- Use constructor or setter guards for invariants that must never be violated in memory.
- Use database constraints for invariants that must never be violated at rest.
- Use service-layer validation for invariants that depend on current persisted state.
- Keep enum usage explicit for statuses, roles, providers, and accounting categories.

## Best Practices To Enforce

- Prefer value objects like `Money` for paired fields that must stay together.
- Validate scale, precision, and units on monetary and measured fields.
- Add optimistic locking to inventory-like or frequently updated entities.
- Keep audit fields consistent across aggregates.
- Avoid anemic "everything is public mutable state" entities; expose only the mutability the domain needs.
- When a field should be immutable after creation, encode that through API design and persistence config.

## Clean Code Standard

- Entity names match domain language.
- Field names are explicit and stable.
- Relationship mappings are readable without guessing intent.
- Comments are only used for non-obvious persistence or concurrency decisions.
