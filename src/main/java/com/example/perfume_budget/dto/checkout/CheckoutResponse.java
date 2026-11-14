package com.example.perfume_budget.dto.checkout;

import com.example.perfume_budget.dto.payment.response.PaystackInitiateTransactionResponse;
import com.example.perfume_budget.enums.PaymentStatus;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * Checkout result. {@code status}/{@code message}/{@code data} keep the Paystack initialise shape (back compatible);
 * the order fields are new (PAY-01).
 */
@Builder
public record CheckoutResponse(
        boolean status,
        String message,
        PaystackInitiateTransactionResponse.Data data,
        Long orderId,
        String orderNumber,
        PaymentStatus paymentStatus,
        String totalAmount,
        LocalDateTime expiresAt,
        boolean idempotentReplay
) {}
