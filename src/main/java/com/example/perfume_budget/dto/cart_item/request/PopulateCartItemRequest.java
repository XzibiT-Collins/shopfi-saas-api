package com.example.perfume_budget.dto.cart_item.request;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record PopulateCartItemRequest(
        @NotNull
        List<CartItemRequest> cartItems
) {}
