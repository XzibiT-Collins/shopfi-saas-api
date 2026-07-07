package com.example.perfume_budget.dto.category.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CategoryRequest(
        @NotNull @NotBlank String categoryName,
        @NotNull @NotBlank String description
) {
}
