package com.example.perfume_budget.mapper;

import com.example.perfume_budget.dto.category.response.CategoryResponse;
import com.example.perfume_budget.dto.product.response.ProductDetails;
import com.example.perfume_budget.dto.product.response.ProductListing;
import com.example.perfume_budget.dto.product.response.ProductVariantSummaryResponse;
import com.example.perfume_budget.model.Category;
import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.pricing.EffectivePrice;

public class ProductMapper {
    private ProductMapper(){
        throw new IllegalStateException("Utility class");
    }

    public static ProductListing toProductListing(Product product, EffectivePrice effectivePrice){
        int stockQuantity = product.getStockQuantity() != null ? product.getStockQuantity() : 0;
        return ProductListing.builder()
                .productId(product.getId())
                .productName(product.getName())
                .productShortDescription(product.getShortDescription())
                .productImageUrl(product.getImageUrl())
                .categoryName(product.getCategory() != null ? product.getCategory().getName() : "")
                .price(effectivePrice.effectiveDisplay())
                .originalPrice(effectivePrice.originalDisplay())
                .onSale(effectivePrice.onSale())
                .discountPercentage(effectivePrice.discountPercentage())
                .discountEndsAt(effectivePrice.endsAt())
                .isOutOfStock(stockQuantity <= 0)
                .isActive(Boolean.TRUE.equals(product.getIsActive()))
                .isEnlisted(Boolean.TRUE.equals(product.getIsEnlisted()))
                .stockQuantity(stockQuantity)
                .slug(product.getSlug())
                .build();
    }

    public static ProductVariantSummaryResponse toProductVariantSummary(Product product) {
        return new ProductVariantSummaryResponse(
                product.getId(),
                product.getSku(),
                product.getName()
        );
    }

    public static ProductDetails toProductDetails(Product product, EffectivePrice effectivePrice){
        Category category = product.getCategory();
        int stockQuantity = product.getStockQuantity() != null ? product.getStockQuantity() : 0;
        return ProductDetails.builder()
                .productId(product.getId() != null ? product.getId() : 0L)
                .productName(product.getName())
                .productShortDescription(product.getShortDescription())
                .productDescription(product.getDescription())
                .productImageUrl(product.getImageUrl())
                .category(category != null ? CategoryResponse.builder()
                        .categoryName(category.getName())
                        .categoryId(category.getId())
                        .description(category.getDescription())
                        .build(): null)
                .sellingPrice(effectivePrice.effectiveDisplay())
                .originalPrice(effectivePrice.originalDisplay())
                .onSale(effectivePrice.onSale())
                .discountPercentage(effectivePrice.discountPercentage())
                .discountEndsAt(effectivePrice.endsAt())
                .costPrice(product.getCostPrice() != null ? product.getCostPrice().toString() : "0.00")
                .stockKeepingUnit(product.getSku())
                .isOutOfStock(stockQuantity <= 0)
                .stockQuantity(stockQuantity)
                .soldCount(product.getSoldCount() != null ? product.getSoldCount() : 0)
                .lowStockThreshold(product.getLowStockThreshold() != null ? product.getLowStockThreshold() : 0)
                .isActive(Boolean.TRUE.equals(product.getIsActive()))
                .isEnlisted(Boolean.TRUE.equals(product.getIsEnlisted()))
                .isFeatured(Boolean.TRUE.equals(product.getIsFeatured()))
                .slug(product.getSlug())
                .build();
    }
}
