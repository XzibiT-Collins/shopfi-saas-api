package com.example.perfume_budget.dto.feature_flag.request;

import jakarta.validation.constraints.NotNull;

public record FeatureFlagUpdateRequest(
        @NotNull Boolean adminEnabled,
        @NotNull Boolean frontDeskEnabled,
        @NotNull Boolean customerEnabled
) {
}
