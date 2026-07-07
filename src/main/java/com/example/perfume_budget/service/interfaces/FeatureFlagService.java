package com.example.perfume_budget.service.interfaces;

import com.example.perfume_budget.dto.feature_flag.request.FeatureFlagUpdateRequest;
import com.example.perfume_budget.dto.feature_flag.response.FeatureFlagResponse;
import com.example.perfume_budget.enums.FeatureFlagKey;
import com.example.perfume_budget.enums.UserRole;

import java.util.List;

public interface FeatureFlagService {
    boolean isEnabled(FeatureFlagKey featureKey, UserRole role);

    List<FeatureFlagResponse> getAllFlags();

    FeatureFlagResponse getFlag(FeatureFlagKey featureKey);

    FeatureFlagResponse updateFlag(FeatureFlagKey featureKey, FeatureFlagUpdateRequest request);
}
