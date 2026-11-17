package com.example.perfume_budget.mapper;

import com.example.perfume_budget.dto.product.response.MostPurchaseProductResponse;
import com.example.perfume_budget.projection.TopProduct;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TopProductMapperTest {

    @Mock
    private TopProduct topProduct;

    @Test
    void toMostPurchasedProduct_Success() {
        when(topProduct.getId()).thenReturn(1L);
        when(topProduct.getProductName()).thenReturn("P1");
        when(topProduct.getViewCount()).thenReturn(100L);
        when(topProduct.getAddToCartCount()).thenReturn(50L);
        when(topProduct.getSoldCount()).thenReturn(10L);

        MostPurchaseProductResponse response = TopProductMapper.toMostPurchasedProduct(topProduct);

        assertNotNull(response);
        assertEquals(new BigDecimal("20.00"), response.conversionRate());
    }

    @Test
    void toMostPurchasedProduct_ZeroAddToCart() {
        when(topProduct.getAddToCartCount()).thenReturn(0L);

        MostPurchaseProductResponse response = TopProductMapper.toMostPurchasedProduct(topProduct);

        assertEquals(BigDecimal.ZERO, response.conversionRate());
    }

    @Test
    void toMostPurchasedProduct_NullCountersMapToZero() {
        // CAT-02: nullable counter columns with no DB default used to NPE here (500 on /admin/metrics/top-products)
        when(topProduct.getAddToCartCount()).thenReturn(null);
        when(topProduct.getSoldCount()).thenReturn(null);
        when(topProduct.getViewCount()).thenReturn(null);

        MostPurchaseProductResponse response = TopProductMapper.toMostPurchasedProduct(topProduct);

        assertEquals(0L, response.addToCartCount());
        assertEquals(0L, response.soldCount());
        assertEquals(0L, response.viewCount());
        assertEquals(BigDecimal.ZERO, response.conversionRate());
    }

    @Test
    void toMostPurchasedProduct_NullSoldWithCartsIsZeroRate() {
        when(topProduct.getAddToCartCount()).thenReturn(4L);
        when(topProduct.getSoldCount()).thenReturn(null);

        MostPurchaseProductResponse response = TopProductMapper.toMostPurchasedProduct(topProduct);

        assertEquals(4L, response.addToCartCount());
        assertEquals(0, new BigDecimal("0.00").compareTo(response.conversionRate()));
    }

    @Test
    void toMostPurchasedProduct_OneCartOneSaleIsHundredPercent() {
        when(topProduct.getAddToCartCount()).thenReturn(1L);
        when(topProduct.getSoldCount()).thenReturn(1L);
        when(topProduct.getViewCount()).thenReturn(7L);

        MostPurchaseProductResponse response = TopProductMapper.toMostPurchasedProduct(topProduct);

        assertEquals(new BigDecimal("100.00"), response.conversionRate());
        assertEquals(7L, response.viewCount());
        assertEquals(1L, response.soldCount());
    }
}
