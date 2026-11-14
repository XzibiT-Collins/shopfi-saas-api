package com.example.perfume_budget.dto.payment.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PaystackWebhook(
        String event,
        WebhookData data
){}