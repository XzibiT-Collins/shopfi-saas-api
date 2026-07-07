package com.example.perfume_budget.dto.walk_in.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record WalkInOrderItemRequest(
        @NotNull Long productId,
        @NotNull @Positive Integer quantity
) {
}
