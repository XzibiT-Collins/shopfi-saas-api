package com.example.perfume_budget.dto.product.response;

import com.example.perfume_budget.dto.category.response.CategoryResponse;
import com.example.perfume_budget.enums.CurrencyCode;
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
        String slug,
        // API-03: the same prices as numbers (the strings above are display text, "GHS 45.00"; clients must not parse them)
        BigDecimal sellingPriceAmount,   // effective (discounted) price
        BigDecimal originalPriceAmount,  // pre-discount selling price
        BigDecimal costPriceAmount,      // null when the product has no cost price
        CurrencyCode currency,
        // SC-BE-01 (admin only, null when the product has no family): pack size info for the stock conversion screen
        Long familyId,
        Boolean isBaseUnit,
        Integer conversionFactor,
        String uomCode
) {}
