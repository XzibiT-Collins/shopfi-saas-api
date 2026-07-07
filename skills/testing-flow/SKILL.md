---
name: testing-flow
description: Use when adding or updating tests for service, mapper, utility, or controller changes in a Spring Boot backend that relies on unit tests for logic integrity.
---

# Testing Flow

Use this skill when changing code in this architecture. Every meaningful change should leave tests clearer than before.

## Goals

- Verify behavior, not implementation trivia.
- Cover domain rules, edge cases, and failure modes.
- Keep tests readable enough to explain the use case.

## Workflow

1. Identify the unit that owns the rule.
2. Write or update tests around the public method of that unit.
3. Cover the happy path first.
4. Add rejection paths for invalid state, permissions, duplicates, not found, and integration failures as applicable.
5. Mock only true collaborators, not the method under test.

## Rules

- Service tests should verify orchestration and rule enforcement.
- Mapper tests should verify field transformation and null/default handling.
- Utility tests should verify deterministic behavior and edge cases.
- Prefer one assertion theme per test method.
- Test names should state scenario and outcome.

## Best Practices To Enforce

- Add tests for every bug fix.
- Cover both explicit custom exceptions and generic framework validation outcomes when behavior matters.
- Verify that side effects happen only when expected, such as event publishing, repository saves, or cookie operations.
- Avoid brittle assertions on logging or exact internal call ordering unless that ordering is part of the contract.

## Clean Code Standard

- Arrange, act, assert blocks stay obvious.
- Fixtures are minimal and intention revealing.
- Repeated setup should be extracted only when it improves readability.
