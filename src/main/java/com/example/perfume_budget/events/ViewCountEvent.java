package com.example.perfume_budget.events;

import lombok.Builder;

@Builder
public record ViewCountEvent(
        Long productId
) {}
