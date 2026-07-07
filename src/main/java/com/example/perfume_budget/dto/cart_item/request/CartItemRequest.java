package com.example.perfume_budget.dto.cart_item.request;

import jakarta.validation.constraints.NotNull;

public record CartItemRequest(
        @NotNull
        Long productId,
        @NotNull
        Integer quantity
) {}
