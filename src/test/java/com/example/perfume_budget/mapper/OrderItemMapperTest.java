package com.example.perfume_budget.mapper;

import com.example.perfume_budget.dto.order_item.OrderItemResponse;
import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.enums.DiscountSource;
import com.example.perfume_budget.model.*;
import com.example.perfume_budget.pricing.EffectivePrice;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class OrderItemMapperTest {

    @Test
    void toOrderItemResponse_Success() {
        OrderItem item = OrderItem.builder()
                .productId(1L).productName("Perfume").quantity(2)
                .unitPrice(new Money(BigDecimal.TEN, CurrencyCode.USD))
                .totalPrice(new Money(BigDecimal.valueOf(20), CurrencyCode.USD))
                .build();

        OrderItemResponse response = OrderItemMapper.toOrderItemResponse(item);

        assertNotNull(response);
        assertEquals(1L, response.productId());
        assertTrue(response.totalPrice().contains("20.00"));
    }

    @Test
    void toOrderItem_Success() {
        Product product = Product.builder().id(1L).name("P").sku("S").price(new Money(BigDecimal.TEN, CurrencyCode.USD)).build();
        CartItem cartItem = CartItem.builder().product(product).quantity(3).build();
        Order order = new Order();
        EffectivePrice effectivePrice = new EffectivePrice(
                BigDecimal.TEN, BigDecimal.TEN, CurrencyCode.USD, false, DiscountSource.NONE, null, BigDecimal.ZERO, null);

        OrderItem result = OrderItemMapper.toOrderItem(cartItem, order, effectivePrice);

        assertNotNull(result);
        assertEquals(1L, result.getProductId());
        assertEquals(3, result.getQuantity());
        assertEquals(0, BigDecimal.TEN.compareTo(result.getUnitPrice().getAmount()));
        assertTrue(result.getTotalPrice().toString().contains("30.00"));
    }

    @Test
    void toOrderItem_NullCartItem_ThrowsException() {
        assertThrows(NullPointerException.class, () -> OrderItemMapper.toOrderItem(null, new Order(), null));
    }
}
