package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.feature_flag.request.FeatureFlagUpdateRequest;
import com.example.perfume_budget.dto.feature_flag.response.FeatureFlagResponse;
import com.example.perfume_budget.enums.FeatureAudience;
import com.example.perfume_budget.enums.FeatureFlagKey;
import com.example.perfume_budget.enums.UserRole;
import com.example.perfume_budget.model.FeatureFlag;
import com.example.perfume_budget.repository.FeatureFlagRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeatureFlagServiceImplTest {

    @Mock
    private FeatureFlagRepository featureFlagRepository;

    @InjectMocks
    private FeatureFlagServiceImpl featureFlagService;

    @Test
    void isEnabled_ReturnsTrueWhenAudienceFlagEnabled() {
        when(featureFlagRepository.findByFeatureKeyAndAudience(FeatureFlagKey.LOGIN_OTP, FeatureAudience.ADMIN))
                .thenReturn(Optional.of(FeatureFlag.builder()
                        .featureKey(FeatureFlagKey.LOGIN_OTP)
                        .audience(FeatureAudience.ADMIN)
                        .enabled(true)
                        .build()));

        boolean enabled = featureFlagService.isEnabled(FeatureFlagKey.LOGIN_OTP, UserRole.ADMIN);

        assertTrue(enabled);
    }

    @Test
    void isEnabled_ReturnsFalseWhenFlagMissing() {
        when(featureFlagRepository.findByFeatureKeyAndAudience(FeatureFlagKey.LOGIN_OTP, FeatureAudience.CUSTOMER))
                .thenReturn(Optional.empty());

        boolean enabled = featureFlagService.isEnabled(FeatureFlagKey.LOGIN_OTP, UserRole.CUSTOMER);

        assertFalse(enabled);
    }

    @Test
    void isEnabled_UsesFrontDeskAudienceForFrontDeskRole() {
        when(featureFlagRepository.findByFeatureKeyAndAudience(FeatureFlagKey.LOGIN_OTP, FeatureAudience.FRONT_DESK))
                .thenReturn(Optional.of(FeatureFlag.builder()
                        .featureKey(FeatureFlagKey.LOGIN_OTP)
                        .audience(FeatureAudience.FRONT_DESK)
                        .enabled(true)
                        .build()));

        boolean enabled = featureFlagService.isEnabled(FeatureFlagKey.LOGIN_OTP, UserRole.FRONT_DESK);

        assertTrue(enabled);
    }

    @Test
    void getFlag_ReturnsAudienceStates() {
        when(featureFlagRepository.findAllByFeatureKeyOrderByAudienceAsc(FeatureFlagKey.LOGIN_OTP))
                .thenReturn(List.of(
                        FeatureFlag.builder()
                                .featureKey(FeatureFlagKey.LOGIN_OTP)
                                .audience(FeatureAudience.ADMIN)
                                .enabled(true)
                                .build(),
                        FeatureFlag.builder()
                                .featureKey(FeatureFlagKey.LOGIN_OTP)
                                .audience(FeatureAudience.FRONT_DESK)
                                .enabled(false)
                                .build(),
                        FeatureFlag.builder()
                                .featureKey(FeatureFlagKey.LOGIN_OTP)
                                .audience(FeatureAudience.CUSTOMER)
                                .enabled(false)
                                .build()
                ));

        FeatureFlagResponse response = featureFlagService.getFlag(FeatureFlagKey.LOGIN_OTP);

        assertEquals(FeatureFlagKey.LOGIN_OTP, response.featureKey());
        assertTrue(response.adminEnabled());
        assertFalse(response.frontDeskEnabled());
        assertFalse(response.customerEnabled());
    }

    @Test
    void updateFlag_UpsertsBothAudiences() {
        when(featureFlagRepository.findByFeatureKeyAndAudience(any(), any())).thenReturn(Optional.empty());
        when(featureFlagRepository.findAllByFeatureKeyOrderByAudienceAsc(FeatureFlagKey.LOGIN_OTP))
                .thenReturn(List.of(
                        FeatureFlag.builder()
                                .featureKey(FeatureFlagKey.LOGIN_OTP)
                                .audience(FeatureAudience.ADMIN)
                                .enabled(true)
                                .build(),
                        FeatureFlag.builder()
                                .featureKey(FeatureFlagKey.LOGIN_OTP)
                                .audience(FeatureAudience.FRONT_DESK)
                                .enabled(false)
                                .build(),
                        FeatureFlag.builder()
                                .featureKey(FeatureFlagKey.LOGIN_OTP)
                                .audience(FeatureAudience.CUSTOMER)
                                .enabled(true)
                                .build()
                ));

        FeatureFlagResponse response = featureFlagService.updateFlag(
                FeatureFlagKey.LOGIN_OTP,
                new FeatureFlagUpdateRequest(true, false, true)
        );

        assertTrue(response.adminEnabled());
        assertFalse(response.frontDeskEnabled());
        assertTrue(response.customerEnabled());
        verify(featureFlagRepository, times(3)).save(any(FeatureFlag.class));
    }
}
