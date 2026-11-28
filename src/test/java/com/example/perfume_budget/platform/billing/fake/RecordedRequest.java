package com.example.perfume_budget.platform.billing.fake;

import java.time.Instant;

/** One request the fake received (the recorder): enough to assert idempotency and key handling, never a secret. */
public record RecordedRequest(Instant at, String method, String path, String query, String reference, Long amount,
                              boolean authorizedWithKnownKey) {
}
