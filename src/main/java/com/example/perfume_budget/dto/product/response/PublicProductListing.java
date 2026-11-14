package com.example.perfume_budget.dto.product.response;

import lombok.Builder;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Storefront (anonymous) catalog row: availability only. It deliberately has NO exact stock quantity and no
 * isActive/isEnlisted flags (public queries only ever return active+enlisted products, so they carry no information
 * and would reveal merchandising state). The admin/front-desk endpoints keep using {@link ProductListing}.
 */
@Builder
public record PublicProductListing(
        Long productId,
        String productName,
        String productShortDescription,
        String productImageUrl,
        String categoryName,
        String price,
        String originalPrice,
        boolean onSale,
        BigDecimal discountPercentage,
        LocalDateTime discountEndsAt,
        boolean isOutOfStock,
        String slug
) implements Serializable {
    private static final long serialVersionUID = 1L;
}
