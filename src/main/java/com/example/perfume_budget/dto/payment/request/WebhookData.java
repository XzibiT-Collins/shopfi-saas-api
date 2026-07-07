package com.example.perfume_budget.dto.payment.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookData(
        long id,
        String domain,
        String status,
        String reference,
        BigDecimal amount,
        String message,
        String gateway_response,
        String paid_at,
        String created_at,
        String channel,
        String currency,
        String ip_address,
        Object metadata,
        Integer fees,
        Object fees_breakdown,
        WebhookAuthorization authorization,
        WebhookCustomer customer,
        BigDecimal requested_amount
) {}