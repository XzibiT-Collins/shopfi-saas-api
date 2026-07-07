package com.example.perfume_budget.mapper;

import com.example.perfume_budget.dto.cart.CartResponse;
import com.example.perfume_budget.dto.cart_item.response.CartItemResponse;
import com.example.perfume_budget.enums.CurrencyCode;
import com.example.perfume_budget.model.Cart;
import com.example.perfume_budget.model.CartItem;
import com.example.perfume_budget.model.Money;
import com.example.perfume_budget.model.ShopWideDiscount;
import com.example.perfume_budget.pricing.EffectivePrice;
import com.example.perfume_budget.service.EffectivePriceService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class CartMapper {
    private CartMapper(){
        throw new IllegalStateException("Utility class");
    }

    public static CartResponse toCartResponse(Cart cart, EffectivePriceService effectivePriceService){
        if (cart == null) return null;

        List<CartItem> cartItems = cart.getItems() != null ? cart.getItems() : List.of();

        // Resolve the active shop discount and "now" once for the whole cart.
        ShopWideDiscount activeShop = effectivePriceService.activeShopDiscount().orElse(null);
        LocalDateTime now = effectivePriceService.now();

        BigDecimal effectiveTotal = BigDecimal.ZERO;
        BigDecimal originalTotal = BigDecimal.ZERO;
        CurrencyCode currency = CurrencyCode.GHS; // Default
        boolean currencyResolved = false;

        List<CartItemResponse> itemResponses = new ArrayList<>();
        for (CartItem item : cartItems) {
            if (item == null || item.getProduct() == null || item.getProduct().getPrice() == null) {
                continue;
            }
            EffectivePrice effectivePrice = effectivePriceService.compute(item.getProduct(), activeShop, now);
            BigDecimal qty = BigDecimal.valueOf(item.getQuantity());
            effectiveTotal = effectiveTotal.add(effectivePrice.effectiveAmount().multiply(qty));
            originalTotal = originalTotal.add(effectivePrice.originalAmount().multiply(qty));
            if (!currencyResolved && effectivePrice.currencyCode() != null) {
                currency = effectivePrice.currencyCode();
                currencyResolved = true;
            }
            itemResponses.add(CartItemMapper.toCartItemResponse(item, effectivePrice));
        }

        return CartResponse.builder()
                .cartItems(itemResponses)
                .totalPrice(new Money(effectiveTotal, currency).toString())
                .originalTotalPrice(new Money(originalTotal, currency).toString())
                .build();
    }
}
