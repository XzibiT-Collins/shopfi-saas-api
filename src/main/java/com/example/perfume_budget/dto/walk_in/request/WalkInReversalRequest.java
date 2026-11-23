package com.example.perfume_budget.dto.walk_in.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /admin/walk-in/orders/{orderNumber}/cancel} and {@code .../refund} (API-RD-03 AC3). Unknown
 * properties are rejected on purpose: a client that sends an {@code amount} expecting a partial refund must get a
 * {@code 400}, not silently a full refund (only full refunds exist).
 */
public record WalkInReversalRequest(
        @NotBlank @Size(max = 255) String reason
) {
    /** Jackson hands every property that is not {@code reason} here (the app-wide mapper ignores unknown ones). */
    @JsonAnySetter
    void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("Unknown property in a walk-in cancel / refund request");
    }
}
