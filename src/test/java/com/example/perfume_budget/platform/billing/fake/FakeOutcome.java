package com.example.perfume_budget.platform.billing.fake;

/** A scripted outcome of one charge (or one verify): what the fake does for the next matching reference. */
public enum FakeOutcome {
    /** Charges; verify says success with the right amount and currency. */
    SUCCESS,
    /** A failed transaction, gateway response "Insufficient Funds". */
    INSUFFICIENT_FUNDS,
    /** A failed transaction, gateway response "Declined". */
    DECLINED,
    /** Processes the charge, then does not answer in time (the client times out: UNKNOWN_OUTCOME). */
    TIMEOUT,
    /** HTTP 500, nothing processed. */
    SERVER_ERROR,
    /** HTTP 502, nothing processed. */
    BAD_GATEWAY,
    /** Charges; verify reports amount + 1 pesewa. */
    WRONG_AMOUNT,
    /** Charges; verify reports currency USD. */
    WRONG_CURRENCY,
    /** Answers "pending" now; success after the configured delay, with a webhook. */
    DELAYED
}
