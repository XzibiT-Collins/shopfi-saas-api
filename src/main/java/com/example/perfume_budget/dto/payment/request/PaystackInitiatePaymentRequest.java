package com.example.perfume_budget.dto.payment.request;

import com.example.perfume_budget.enums.CurrencyCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.math.BigDecimal;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaystackInitiatePaymentRequest(
        String email,
        BigDecimal amount,
        CurrencyCode currency,
        /** Per-tenant return URL (tenant storefront origin + callback path); omitted when it cannot be derived. */
        @JsonProperty("callback_url") String callbackUrl
) {}
