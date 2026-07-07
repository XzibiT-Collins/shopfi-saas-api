package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.feature_flag.request.FeatureFlagUpdateRequest;
import com.example.perfume_budget.dto.feature_flag.response.FeatureFlagResponse;
import com.example.perfume_budget.enums.FeatureAudience;
import com.example.perfume_budget.enums.FeatureFlagKey;
import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.model.FeatureFlag;
import com.example.perfume_budget.repository.FeatureFlagRepository;
import com.example.perfume_budget.service.interfaces.FeatureFlagService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class FeatureFlagServiceImpl implements FeatureFlagService {
    private final FeatureFlagRepository featureFlagRepository;

    @Override
    @Transactional(readOnly = true)
    public boolean isEnabled(FeatureFlagKey featureKey, UserRole role) {
        return featureFlagRepository.findByFeatureKeyAndAudience(featureKey, toAudience(role))
                .map(FeatureFlag::isEnabled)
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FeatureFlagResponse> getAllFlags() {
        return List.of(FeatureFlagKey.values()).stream()
                .map(this::getFlag)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public FeatureFlagResponse getFlag(FeatureFlagKey featureKey) {
        List<FeatureFlag> flags = featureFlagRepository.findAllByFeatureKeyOrderByAudienceAsc(featureKey);
        boolean adminEnabled = flags.stream()
                .filter(flag -> flag.getAudience() == FeatureAudience.ADMIN)
                .findFirst()
                .map(FeatureFlag::isEnabled)
                .orElse(false);
        boolean frontDeskEnabled = flags.stream()
                .filter(flag -> flag.getAudience() == FeatureAudience.FRONT_DESK)
                .findFirst()
                .map(FeatureFlag::isEnabled)
                .orElse(false);
        boolean customerEnabled = flags.stream()
                .filter(flag -> flag.getAudience() == FeatureAudience.CUSTOMER)
                .findFirst()
                .map(FeatureFlag::isEnabled)
                .orElse(false);

        return FeatureFlagResponse.builder()
                .featureKey(featureKey)
                .description(featureKey.getDescription())
                .adminEnabled(adminEnabled)
                .frontDeskEnabled(frontDeskEnabled)
                .customerEnabled(customerEnabled)
                .build();
    }

    @Override
    public FeatureFlagResponse updateFlag(FeatureFlagKey featureKey, FeatureFlagUpdateRequest request) {
        upsert(featureKey, FeatureAudience.ADMIN, request.adminEnabled());
        upsert(featureKey, FeatureAudience.FRONT_DESK, request.frontDeskEnabled());
        upsert(featureKey, FeatureAudience.CUSTOMER, request.customerEnabled());
        return getFlag(featureKey);
    }

    private void upsert(FeatureFlagKey featureKey, FeatureAudience audience, boolean enabled) {
        FeatureFlag featureFlag = featureFlagRepository.findByFeatureKeyAndAudience(featureKey, audience)
                .orElseGet(() -> FeatureFlag.builder()
                        .featureKey(featureKey)
                        .audience(audience)
                        .build());
        featureFlag.setEnabled(enabled);
        featureFlagRepository.save(featureFlag);
    }

    private FeatureAudience toAudience(UserRole role) {
        return switch (role) {
            case ADMIN -> FeatureAudience.ADMIN;
            case FRONT_DESK -> FeatureAudience.FRONT_DESK;
            case CUSTOMER -> FeatureAudience.CUSTOMER;
        };
    }
}
