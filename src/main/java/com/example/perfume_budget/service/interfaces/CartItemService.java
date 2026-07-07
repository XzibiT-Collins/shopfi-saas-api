package com.example.perfume_budget.service.interfaces;

import com.example.perfume_budget.dto.cart_item.request.CartItemRequest;
import com.example.perfume_budget.dto.cart_item.request.CartItemUpdateRequest;
import com.example.perfume_budget.dto.cart_item.response.CartItemResponse;

public interface CartItemService {
    CartItemResponse addItemToCart(CartItemRequest request);
    CartItemResponse updateCartItem(Long cartItemId, CartItemUpdateRequest request);
    void removeCartItem(Long cartItemId);

}
