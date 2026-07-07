package com.example.perfume_budget.dto.product.response;

import com.example.perfume_budget.dto.category.response.CategoryResponse;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Builder
public record ProductDetails(
        long productId,
        String productName,
        String productShortDescription,
        String productDescription,
        String productImageUrl,
        CategoryResponse category,
        String sellingPrice,    // effective (discounted) price
        String originalPrice,   // pre-discount selling price (== sellingPrice when not on sale)
        boolean onSale,
        BigDecimal discountPercentage,
        LocalDateTime discountEndsAt,
        String costPrice,
        String stockKeepingUnit,
        boolean isOutOfStock,
        int soldCount,
        int stockQuantity,
        int lowStockThreshold,
        boolean isActive,
        boolean isEnlisted,
        boolean isFeatured,
        String slug
) {}
