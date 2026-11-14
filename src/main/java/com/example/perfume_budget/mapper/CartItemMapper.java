package com.example.perfume_budget.mapper;

import com.example.perfume_budget.dto.cart_item.request.CartItemRequest;
import com.example.perfume_budget.dto.cart_item.response.CartItemResponse;
import com.example.perfume_budget.model.Cart;
import com.example.perfume_budget.model.CartItem;
import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.pricing.EffectivePrice;
import com.example.perfume_budget.model.Money;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;


@Slf4j
public class CartItemMapper {
    private CartItemMapper(){
        throw new IllegalStateException("Utility class");
    }

    public static CartItemResponse toCartItemResponse(CartItem cartItem, EffectivePrice effectivePrice){
        log.info("Mapping Cart Items");
        return CartItemResponse.builder()
                .cartItemId(cartItem.getId())
                .productId(cartItem.getProduct().getId())
                .productName(cartItem.getProduct().getName())
                .productImageUrl(cartItem.getProduct().getImageUrl())
                .unitPrice(effectivePrice.effectiveDisplay())
                .originalUnitPrice(effectivePrice.originalDisplay())
                .onSale(effectivePrice.onSale())
                .discountPercentage(effectivePrice.discountPercentage())
                .quantity(cartItem.getQuantity())
                .lineTotal(lineTotal(effectivePrice, cartItem.getQuantity()))
                .build();
    }

    private static String lineTotal(EffectivePrice price, Integer quantity) {
        BigDecimal total = price.effectiveAmount().multiply(BigDecimal.valueOf(quantity == null ? 0 : quantity));
        return price.currencyCode() == null ? total.toPlainString() : new Money(total, price.currencyCode()).toString();
    }

    public static CartItem toCartItem(Product product, Cart cart, CartItemRequest request){
        return CartItem.builder()
                .cart(cart)
                .product(product)
                .quantity(request.quantity())
                .unitPrice(product.getPrice())
                .build();
    }
}
