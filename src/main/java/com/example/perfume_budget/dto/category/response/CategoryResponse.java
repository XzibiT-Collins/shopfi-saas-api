package com.example.perfume_budget.dto.category.response;

import lombok.Builder;

@Builder
public record CategoryResponse(
        Long categoryId,
        String categoryName,
        String description,
        String slug
){}
