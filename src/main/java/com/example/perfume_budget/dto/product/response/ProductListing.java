package com.example.perfume_budget.dto.product.response;

import lombok.Builder;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Builder
public record ProductListing(
        Long productId,
        String productName,
        String productShortDescription,
        String productImageUrl,
        String categoryName,
        String price,           // effective (discounted) price the customer pays
        String originalPrice,   // pre-discount price (== price when not on sale)
        boolean onSale,
        BigDecimal discountPercentage,
        LocalDateTime discountEndsAt,
        boolean isOutOfStock,
        boolean isActive,
        boolean isEnlisted,
        String slug,
        Integer stockQuantity
) implements Serializable {
    private static final long serialVersionUID = 1L;
}
