package com.example.perfume_budget.mapper;

import com.example.perfume_budget.dto.cart_item.request.CartItemRequest;
import com.example.perfume_budget.dto.cart_item.response.CartItemResponse;
import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.enums.DiscountSource;
import com.example.perfume_budget.model.Cart;
import com.example.perfume_budget.model.CartItem;
import com.example.perfume_budget.model.Money;
import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.pricing.EffectivePrice;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class CartItemMapperTest {

    @Test
    void toCartItemResponse_Success() {
        Product product = Product.builder().id(1L).name("Perfume").imageUrl("url").build();
        CartItem cartItem = CartItem.builder()
                .id(10L)
                .product(product)
                .unitPrice(new Money(BigDecimal.TEN, CurrencyCode.USD))
                .quantity(2)
                .build();

        EffectivePrice effectivePrice = new EffectivePrice(BigDecimal.TEN, BigDecimal.TEN, CurrencyCode.USD,
                false, DiscountSource.NONE, null, BigDecimal.ZERO, null);

        CartItemResponse response = CartItemMapper.toCartItemResponse(cartItem, effectivePrice);

        assertNotNull(response);
        assertEquals(10L, response.cartItemId());
        assertEquals(1L, response.productId());
        assertEquals("Perfume", response.productName());
        assertEquals("url", response.productImageUrl());
        assertEquals(2, response.quantity());
        assertTrue(response.unitPrice().contains("10.00"));
    }

    @Test
    void toCartItemResponse_NullInput_ThrowsException() {
        assertThrows(NullPointerException.class, () -> CartItemMapper.toCartItemResponse(null, null));
    }

    @Test
    void toCartItemResponse_NullProduct_ThrowsException() {
        CartItem cartItem = CartItem.builder().id(1L).product(null).build();
        assertThrows(NullPointerException.class, () -> CartItemMapper.toCartItemResponse(cartItem, null));
    }

    @Test
    void toCartItem_Success() {
        Product product = Product.builder().id(1L).price(new Money(BigDecimal.ONE, CurrencyCode.USD)).build();
        Cart cart = Cart.builder().id(5L).build();
        CartItemRequest request = new CartItemRequest(1L, 3);

        CartItem result = CartItemMapper.toCartItem(product, cart, request);

        assertNotNull(result);
        assertEquals(product, result.getProduct());
        assertEquals(cart, result.getCart());
        assertEquals(3, result.getQuantity());
        assertEquals(product.getPrice(), result.getUnitPrice());
    }

    @Test
    void toCartItem_NullInputs_ThrowsException() {
        assertThrows(NullPointerException.class, () -> CartItemMapper.toCartItem(null, new Cart(), new CartItemRequest(1L, 1)));
    }
}
