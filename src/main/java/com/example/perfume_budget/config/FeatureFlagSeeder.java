package com.example.perfume_budget.config;

import com.example.perfume_budget.enums.FeatureAudience;
import com.example.perfume_budget.enums.FeatureFlagKey;
import com.example.perfume_budget.model.FeatureFlag;
import com.example.perfume_budget.repository.FeatureFlagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FeatureFlagSeeder implements ApplicationRunner {
    private final FeatureFlagRepository featureFlagRepository;

    @Override
    public void run(ApplicationArguments args) {
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
