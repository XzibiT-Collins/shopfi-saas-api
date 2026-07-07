package com.example.perfume_budget.dto.product.response;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record MostPurchaseProductResponse(
        Long productId,
        String productName,
        Long viewCount,
        Long addToCartCount,
        Long soldCount,
        BigDecimal conversionRate
) {}
