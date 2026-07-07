package com.example.perfume_budget.dto.walk_in.response;

import lombok.Builder;

@Builder
public record WalkInOrderItemResponse(
        String productName,
        String sku,
        Integer quantity,
        String unitPrice,
        String totalPrice
) {
}
