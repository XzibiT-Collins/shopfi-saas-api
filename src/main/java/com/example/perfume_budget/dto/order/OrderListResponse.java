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
        PaymentStatus paymentStatus,
        OrderProcessingStatus deliveryStatus,
        String totalAmount,
        /** @deprecated zone-less server-local time; use {@code orderedAt}. */
        @Deprecated LocalDateTime orderDate,
        OffsetDateTime orderedAt,     // the same instant in UTC with an explicit offset (ISO-8601, "...Z")
        Long version                  // optimistic-concurrency token for PUT /order/update-order-status
){}
