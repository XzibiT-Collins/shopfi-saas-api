package com.example.perfume_budget.dto.payment.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookAuthorization(
        String authorization_code,
        String bin,
        String last4,
        String exp_month,
        String exp_year,
        String channel,
        String card_type,
        String bank,
        String country_code,
        String brand,
        boolean reusable,
        String signature,
        String account_name
) {}
