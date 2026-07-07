package com.example.perfume_budget.dto.cart;

import com.example.perfume_budget.dto.cart_item.response.CartItemResponse;
import lombok.Builder;

import java.util.List;

@Builder
public record CartResponse(
        List<CartItemResponse> cartItems,
        String totalPrice,          // effective (discounted) total
        String originalTotalPrice   // pre-discount total (== totalPrice when nothing on sale)
){}
