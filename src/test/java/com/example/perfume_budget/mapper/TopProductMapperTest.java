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
}
