package com.example.perfume_budget.dto.feature_flag.response;

import com.example.perfume_budget.enums.FeatureFlagKey;
import lombok.Builder;

@Builder
public record FeatureFlagResponse(
        FeatureFlagKey featureKey,
        String description,
        boolean adminEnabled,
        boolean frontDeskEnabled,
        boolean customerEnabled
) {
}
