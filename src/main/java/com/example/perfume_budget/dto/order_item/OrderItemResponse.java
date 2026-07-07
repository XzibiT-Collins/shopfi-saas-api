package com.example.perfume_budget.dto.order_item;

import lombok.Builder;

@Builder
public record OrderItemResponse(
        Long productId,
        String productName,
        Integer quantity,
        String unitPrice,
        String totalPrice
) {}
