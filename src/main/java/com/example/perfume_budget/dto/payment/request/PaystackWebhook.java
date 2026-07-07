package com.example.perfume_budget.dto.payment.request;

public record PaystackWebhook(
        String event,
        WebhookData data
){}
