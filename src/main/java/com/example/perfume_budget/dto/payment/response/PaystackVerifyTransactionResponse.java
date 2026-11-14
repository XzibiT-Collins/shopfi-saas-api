package com.example.perfume_budget.dto.payment.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/** Subset of Paystack's GET /transaction/verify/:reference response that we rely on. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaystackVerifyTransactionResponse(
        boolean status,
        String message,
        Data data
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(
            String status,                         // "success" | "failed" | "abandoned" | "pending" | ...
            String reference,
            BigDecimal amount,                     // minor units (pesewas)
            String currency,
            String channel,
            @JsonProperty("gateway_response") String gatewayResponse
    ) {}
}
