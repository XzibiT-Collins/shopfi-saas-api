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
        // CAT-02: the counter columns are nullable with no DB default (the query also COALESCEs): NULL counts as 0
        long addToCart = orZero(product.getAddToCartCount());
        long sold = orZero(product.getSoldCount());
        BigDecimal conversionRate = addToCart > 0
                ? BigDecimal.valueOf((double) sold / addToCart * 100)
                .setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        return MostPurchaseProductResponse.builder()
                .productId(product.getId())
                .productName(product.getProductName())
                .viewCount(orZero(product.getViewCount()))
                .addToCartCount(addToCart)
                .soldCount(sold)
                .conversionRate(conversionRate)
                .build();
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }
}
