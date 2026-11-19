package com.example.perfume_budget.dto.order;

import com.example.perfume_budget.enums.OrderProcessingStatus;
import com.example.perfume_budget.enums.PaymentStatus;
import lombok.Builder;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

@Builder
public record OrderListResponse (
        Long orderId,
        String orderNumber,
        String reference,
        boolean refundRequired,
        boolean cancellable,          // API-02: true while the owner may POST /api/v1/order/{orderNumber}/cancel (PENDING/INITIATED)
        PaymentStatus paymentStatus,
        OrderProcessingStatus deliveryStatus,
        String totalAmount,
        /** DEPRECATED: zone-less server-local time; use {@code orderedAt}. */
        LocalDateTime orderDate,
        OffsetDateTime orderedAt,     // the same instant in UTC with an explicit offset (ISO-8601, "...Z")
        Long version                  // optimistic-concurrency token for PUT /order/update-order-status
){}
