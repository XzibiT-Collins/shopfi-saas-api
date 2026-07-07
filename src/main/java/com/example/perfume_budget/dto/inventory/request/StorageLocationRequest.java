package com.example.perfume_budget.dto.inventory.request;

import com.example.perfume_budget.enums.StorageLocationType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record StorageLocationRequest(
        @NotBlank @Size(max = 128) String name,
        @NotNull StorageLocationType type,
        @Min(0) Integer lowStockThreshold,
        Boolean active
) {
}
