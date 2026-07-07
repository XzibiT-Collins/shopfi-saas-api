package com.example.perfume_budget.dto.payment.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

@Builder
public record PaystackInitiateTransactionResponse(
        boolean status,
        String message,
        Data data
) {
    public record Data(
            @JsonProperty("authorization_url") String authorizationUrl,
            @JsonProperty("access_code") String accessCode,
            String reference
    ) {}
}
