package com.example.perfume_budget.service.interfaces;

import com.example.perfume_budget.dto.cart.CartResponse;
import com.example.perfume_budget.dto.cart_item.request.PopulateCartItemRequest;


public interface CartService {
    CartResponse getCart();
    void clearCart();
    CartResponse populateCartFromLocalStorage(PopulateCartItemRequest requestList);
}
