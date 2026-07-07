package com.example.perfume_budget.dto.payment.request;

import com.example.perfume_budget.enums.CurrencyCode;
import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record PaystackInitiatePaymentRequest(
        String email,
        BigDecimal amount,
        CurrencyCode currency
) {}
