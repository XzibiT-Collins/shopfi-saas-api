package com.example.perfume_budget.mapper;

import com.example.perfume_budget.dto.product.response.MostPurchaseProductResponse;
import com.example.perfume_budget.projection.TopProduct;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class TopProductMapper {
    private TopProductMapper(){
        throw new IllegalStateException("Utility class");
    }

    public static MostPurchaseProductResponse toMostPurchasedProduct(TopProduct product){
        BigDecimal conversionRate = product.getAddToCartCount() > 0
                ? BigDecimal.valueOf((double) product.getSoldCount() / product.getAddToCartCount() * 100)
                .setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        return MostPurchaseProductResponse.builder()
                .productId(product.getId())
                .productName(product.getProductName())
                .viewCount(product.getViewCount())
                .addToCartCount(product.getAddToCartCount())
                .soldCount(product.getSoldCount())
                .conversionRate(conversionRate)
                .build();
    }
}
