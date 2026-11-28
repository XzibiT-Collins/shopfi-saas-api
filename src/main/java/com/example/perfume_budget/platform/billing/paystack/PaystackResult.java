package com.example.perfume_budget.platform.billing.paystack;

/**
 * Typed outcome of one platform Paystack call (BILL-01 AC6). A caller branches on the type; there is no provider text in
 * any case (billing.md 17.3: provider messages are never returned or logged).
 * <ul>
 *   <li>{@link Ok}: the call succeeded; a charge or verify can still carry a FAILED transaction status inside the value;</li>
 *   <li>{@link Rejected}: Paystack answered a 4xx (or {@code status:false}); the request was understood and refused;</li>
 *   <li>{@link KeyRejected}: 401 / 403, OUR key is wrong or revoked: a platform fault, never a member fault;</li>
 *   <li>{@link UnknownOutcome}: the request was sent and no usable answer came back (timeout, dropped connection, garbage):
 *       a charge may or may not have happened, so the caller resolves it with {@code verify}, NEVER by charging again (S4);</li>
 *   <li>{@link Unavailable}: nothing was sent or Paystack is overloaded (circuit open, bulkhead full, 5xx, 429, connect failure,
 *       a checkout URL on another host);</li>
 *   <li>{@link NotConfigured}: no keys stored for the environment.</li>
 * </ul>
 */
public sealed interface PaystackResult<T> {

    record Ok<T>(T value) implements PaystackResult<T> {
    }

    record Rejected<T>(int httpStatus) implements PaystackResult<T> {
    }

    record KeyRejected<T>() implements PaystackResult<T> {
    }

    record UnknownOutcome<T>() implements PaystackResult<T> {
    }

    record Unavailable<T>(Reason reason) implements PaystackResult<T> {
    }

    record NotConfigured<T>() implements PaystackResult<T> {
    }

    enum Reason { CIRCUIT_OPEN, BULKHEAD_FULL, SERVER_ERROR, RATE_LIMITED, CONNECT_FAILED, BAD_CHECKOUT_URL }
}
