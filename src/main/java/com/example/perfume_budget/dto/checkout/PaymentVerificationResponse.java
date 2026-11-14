package com.example.perfume_budget.dto.checkout;

import com.example.perfume_budget.enums.PaymentStatus;
import lombok.Builder;

/**
 * State of one payment as the server knows it. {@code paymentStatus} is the ORDER's payment status (same value
 * GET /api/v1/order/{n} returns); INITIATED/PENDING mean "not settled yet".
 */
@Builder
public record PaymentVerificationResponse(
        Long orderId,
        String orderNumber,
        String reference,
        PaymentStatus paymentStatus,
        boolean settled,         // true once COMPLETED, FAILED or CANCELLED
        boolean refundRequired   // paid, but the order could not be fulfilled any more
) {}
