package com.example.perfume_budget.dto.order;

import com.example.perfume_budget.enums.OrderProcessingStatus;
import com.example.perfume_budget.enums.PaymentStatus;
import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record OrderListResponse (
        Long orderId,
        String orderNumber,
        PaymentStatus paymentStatus,
        OrderProcessingStatus deliveryStatus,
        String totalAmount,
        LocalDateTime orderDate
){}
