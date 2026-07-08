package com.example.perfume_budget.config;

import com.example.perfume_budget.enums.FeatureAudience;
import com.example.perfume_budget.enums.FeatureFlagKey;
import com.example.perfume_budget.model.FeatureFlag;
import com.example.perfume_budget.repository.FeatureFlagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Seeds feature flags (all disabled by default). Idempotent; invoked per-tenant by
 * {@link TenantSeeder} under an active {@code TenantContext} (no longer a boot-time runner).
 */
@Component
@RequiredArgsConstructor
public class FeatureFlagSeeder {
    private final FeatureFlagRepository featureFlagRepository;

    public void seed() {
        for (FeatureFlagKey featureKey : FeatureFlagKey.values()) {
            for (FeatureAudience audience : FeatureAudience.values()) {
                featureFlagRepository.findByFeatureKeyAndAudience(featureKey, audience)
                        .orElseGet(() -> featureFlagRepository.save(FeatureFlag.builder()
                                .featureKey(featureKey)
                                .audience(audience)
                                .enabled(false)
                                .build()));
            }
        }
    }
}
