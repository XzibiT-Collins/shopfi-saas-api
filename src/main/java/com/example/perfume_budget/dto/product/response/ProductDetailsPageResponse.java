package com.example.perfume_budget.dto.product.response;

import lombok.Builder;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Builder
public record ProductDetailsPageResponse(
        long productId,
        String productName,
        String productShortDescription,
        String productDescription,
        String productImageUrl,
        String category,
        String sellingPrice,    // effective (discounted) price
        String originalPrice,   // pre-discount selling price (== sellingPrice when not on sale)
        boolean onSale,
        BigDecimal discountPercentage,
        LocalDateTime discountEndsAt,
        boolean isOutOfStock,
//        boolean isActive,
        boolean isFeatured,
        String slug
) implements Serializable {
    private static final long serialVersionUID = 1L;
}
