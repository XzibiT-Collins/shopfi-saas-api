package com.example.perfume_budget.dto.cart_item.response;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record CartItemResponse(
        Long cartItemId,
        Long productId,
        String productName,
        String productImageUrl,
        String unitPrice,          // effective (discounted) per-unit price
        String originalUnitPrice,  // pre-discount per-unit price (== unitPrice when not on sale)
        boolean onSale,
        BigDecimal discountPercentage,
        Integer quantity
) {}
