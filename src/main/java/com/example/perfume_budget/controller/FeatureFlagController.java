package com.example.perfume_budget.controller;

import com.example.perfume_budget.dto.CustomApiResponse;
import com.example.perfume_budget.dto.feature_flag.request.FeatureFlagUpdateRequest;
import com.example.perfume_budget.dto.feature_flag.response.FeatureFlagResponse;
import com.example.perfume_budget.enums.FeatureFlagKey;
import com.example.perfume_budget.service.interfaces.FeatureFlagService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/feature-flags")
@RequiredArgsConstructor
public class FeatureFlagController {
    private final FeatureFlagService featureFlagService;

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<CustomApiResponse<List<FeatureFlagResponse>>> getAllFlags() {
        return ResponseEntity.ok(CustomApiResponse.success(featureFlagService.getAllFlags()));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{featureKey}")
    public ResponseEntity<CustomApiResponse<FeatureFlagResponse>> getFlag(@PathVariable FeatureFlagKey featureKey) {
        return ResponseEntity.ok(CustomApiResponse.success(featureFlagService.getFlag(featureKey)));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{featureKey}")
    public ResponseEntity<CustomApiResponse<FeatureFlagResponse>> updateFlag(@PathVariable FeatureFlagKey featureKey,
                                                                             @Valid @RequestBody FeatureFlagUpdateRequest request) {
        return ResponseEntity.ok(CustomApiResponse.success(featureFlagService.updateFlag(featureKey, request)));
    }
}
