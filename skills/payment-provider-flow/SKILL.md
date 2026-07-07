---
name: payment-provider-flow
description: Use when integrating or refactoring a third-party payment provider in a Spring Boot backend, including provider initiation APIs, local payment persistence, webhook verification, async processing, reconciliation, idempotency, and failure recovery.
---

# Payment Provider Flow

Use this skill when adding a payment provider such as Paystack, Hubtel, Stripe, Flutterwave, or another gateway to a layered Spring Boot backend.

## Goals

- Persist local payment intent before handing control to the provider.
- Treat the provider as an external system that can fail, retry, duplicate, delay, or send conflicting status updates.
- Make payment completion idempotent and webhook-driven.
- Reconcile payment outcomes with orders, stock, carts, notifications, and bookkeeping safely.

## Architecture

- `controller`: thin endpoints for checkout initiation or provider callbacks.
- `service`: owns payment orchestration and reconciliation rules.
- `gateway service`: provider-specific HTTP client and request or response mapping.
- `webhook service`: signature verification and early rejection only.
- `webhook processor`: async parsing, idempotency checks, state transitions, and downstream effects.
- `model`: local `Payment` record with provider, local reference, provider reference, amount, currency, status, payload, and timestamps.
- `repository`: lookup by local reference and provider reference.

## Core Flow

1. Build the order or payment intent from trusted local state.
2. Persist a local `Payment` row first with:
   - system reference
   - provider
   - amount and currency
   - customer identifier
   - initial status such as `PENDING`
3. Reserve stock or lock the commercial state before initiating payment if the business requires it.
4. Call the provider through a dedicated gateway service.
5. If initiation succeeds:
   - store provider reference
   - move local payment to `INITIATED`
   - return provider redirect or authorization payload
6. If initiation fails:
   - mark local payment `FAILED`
   - store failure reason
   - release reserved stock or rollback business state as needed
7. Accept provider webhooks through a dedicated endpoint.
8. Verify webhook signature before any business processing.
9. Process the webhook asynchronously and idempotently.
10. Reconcile downstream systems only after the payment state transition is valid.

## Provider Integration Rules

- One provider gets one dedicated gateway implementation.
- Keep provider DTOs provider-specific. Do not leak them across the app.
- Put provider secret, base URL, callback URL, and timeout config in properties.
- Map provider statuses into local `PaymentStatus` values explicitly.
- Normalize provider amounts, currencies, and references before comparison.
- Do not let provider SDK or HTTP client exceptions escape directly to controllers.

## Local Payment Record Requirements

- unique local system reference
- provider enum
- provider payment reference when available
- order association or business aggregate association
- status lifecycle
- amount and currency
- customer identity needed for reconciliation
- failure reason
- raw gateway response or payload for audit
- created and updated timestamps

## Webhook Rules

- Webhook controller should only accept raw payload and signature header, then delegate.
- Verify signature using the provider-prescribed algorithm on the raw body.
- Reject invalid signatures with `403`.
- Return quickly after verification and hand off to async processing.
- Treat every webhook as retryable and possibly duplicated.
- Store the raw payload for audit and debugging.

## Idempotency And Reconciliation

- Never process a completed, failed, or cancelled payment twice.
- Use provider reference plus status checks to make completion idempotent.
- If the provider sends the same success event multiple times, process once and ignore the rest.
- If the provider reports failure after success, do not blindly downgrade state. Define allowed transitions.
- Keep a clear state machine:
  - `PENDING` -> `INITIATED`
  - `INITIATED` -> `COMPLETED`
  - `INITIATED` -> `FAILED`
  - `INITIATED` -> `CANCELLED`
- Protect against missing local records. Log and return safely.

## Downstream Side Effects

On successful payment, consider these in order:

- update local payment state
- update order payment status
- persist provider and local references
- clear cart if checkout semantics require it
- update sold counts or inventory metrics
- record bookkeeping or ledger entries
- publish domain events for notifications

On failed payment:

- update local payment state
- update order payment status if required
- release reserved stock
- preserve failure reason and raw payload

## Transaction Rules

- Use `@Transactional` on reconciliation workflows that must keep payment, order, and stock updates consistent.
- Avoid doing signature verification inside large transactions.
- Keep remote provider calls outside of long-running database transactions when possible.
- If you use async webhook processing, ensure the processor owns the transaction boundary.

## Best Practices To Enforce

- Prefer webhook-confirmed completion over trusting the browser redirect alone.
- Use constant-time comparison for webhook signature validation where practical.
- Add provider request timeouts and explicit handling for `4xx`, `5xx`, and network failures.
- Persist enough state to retry or manually reconcile a stuck payment.
- Add a reconciliation path for payments initiated locally but never confirmed by webhook.
- Make stock reservation and release behavior explicit, not incidental.
- Keep payment initiation and payment confirmation as separate concerns.
- Use structured logging with provider, system reference, and provider reference, but never log secrets.
- Avoid storing full sensitive card or wallet data. Store only allowed audit fields.

## Clean Code Standard

- Provider client code should read like an adapter, not a business service.
- Payment orchestration should use domain terms like `checkout`, `handlePaymentSuccess`, `handlePaymentFailure`, `processWebhook`.
- Keep provider-specific branching out of controllers.
- Extract helper methods for state transitions, reconciliation, and payload parsing.

## Test Expectations

- initiation success
- provider initiation failure for `4xx`, `5xx`, and connectivity issues
- invalid webhook signature
- valid webhook signature
- webhook payment-not-found path
- duplicate webhook idempotency
- success reconciliation path
- failure reconciliation path
- stock release on failed payment
- bookkeeping and event publishing on successful payment
- disallowed or conflicting state transition handling

## Common Pitfalls To Avoid

- creating the provider charge before persisting local payment intent
- trusting frontend callback success without server verification
- processing the same webhook more than once
- releasing stock on every non-success webhook without checking prior state
- mixing provider DTOs into core service APIs
- treating payment initiation success as proof of funds captured
