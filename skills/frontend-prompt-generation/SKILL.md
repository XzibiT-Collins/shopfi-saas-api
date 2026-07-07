---
name: frontend-prompt-generation
description: Generate detailed, implementation-ready prompts for frontend work based on existing backend code, DTOs, endpoints, business rules, and project context. Use when Codex needs to prepare a high-specificity handoff prompt for frontend engineers integrating a backend feature, admin flow, dashboard, form workflow, or API-backed UI into an existing frontend codebase.
---

# Frontend Prompt Generation

## Overview

Generate frontend prompts that are specific enough for direct implementation. Build the prompt from repository evidence, not from generic UI assumptions.

## Workflow

1. Read the backend source of truth before drafting:
   - feature docs
   - controller endpoints
   - DTOs
   - service rules
   - validation and security constraints
2. Identify the frontend integration target:
   - new screen
   - admin flow
   - list/detail view
   - form flow
   - route addition
   - API service wiring
3. Extract only the facts the frontend needs:
   - endpoint paths and HTTP methods
   - request and response DTO names
   - wrapper shapes such as `CustomApiResponse<T>` and pagination envelopes
   - validation rules the UI should respect
   - important state transitions and side effects
   - admin/customer access context
4. Write the prompt as an execution handoff, not as a feature summary.
5. Keep the prompt opinionated enough to reduce ambiguity, but do not invent frontend architecture that is not supported by the codebase or request.

## Prompt Structure

Use this structure when the user wants a frontend implementation prompt:

- short title
- context
- primary goal
- backend endpoints
- business rules the UI must respect
- frontend requirements
- expected UX or screens
- implementation guidance
- deliverables
- important constraints

Omit sections only when they are truly irrelevant.

## Required Content

Always include:

- exact endpoint paths
- exact HTTP methods
- request and response type names when available
- response wrapper conventions when relevant
- rules for conditional fields and validation
- whether the flow is admin-only, customer-only, or shared
- whether the UI should reuse existing patterns instead of creating new abstractions

Prefer concrete bullets such as:

- `POST /api/v1/admin/walk-in/order`
- `GET /api/v1/admin/walk-in/orders/{orderNumber}`
- `amountPaid == totalAmount` for card payments

Do not replace exact rules with vague phrases like "handle validation appropriately."

## Quality Bar

A good prompt should:

- tell the frontend exactly which endpoints to call
- explain the business constraints that should shape the UX
- make the expected screens or interactions obvious
- fit into an existing frontend codebase without forcing a rewrite
- be ready to paste into another Codex session with minimal follow-up

A weak prompt usually:

- says "integrate the backend feature" without listing contracts
- ignores wrappers, pagination, or auth context
- leaves conditional form behavior unspecified
- invents UI behavior that contradicts backend rules

## Style Rules

- Write in direct implementation language.
- Use flat bullets for contract-heavy sections.
- Prefer exact backend terminology over paraphrased labels.
- Do not restate internal backend implementation details unless they affect frontend behavior.
- Do not include speculative UI polish unless the user asked for it.
- If the frontend already knows DTOs, avoid re-describing every field unless the field behavior matters to integration.

## Repo-Aware Guidance

When the repository contains an existing frontend or integration prompt, mirror its level of specificity and structure instead of producing a generic template.

When the request is based on a backend feature that was just implemented, use:

- the implementation itself as the primary source of truth
- the feature documentation as secondary support
- tests to confirm edge cases when they clarify behavior

## Output Rule

Default to producing a prompt in Markdown that another engineer or Codex agent can use immediately.

If the user asks to save the prompt, create a clearly named `.md` file at the requested location or, if unspecified, at the project root.
