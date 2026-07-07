package com.example.perfume_budget.dto.payment.request;

public record WebhookCustomer(
        long id,
        String first_name,
        String last_name,
        String email,
        String customer_code,
        String phone,
        String metadata,
        String risk_action,
        String international_format_phone
) {}


