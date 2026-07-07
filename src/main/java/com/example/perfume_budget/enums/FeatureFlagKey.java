package com.example.perfume_budget.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum FeatureFlagKey {
    LOGIN_OTP("Require OTP after successful email/password authentication.");

    private final String description;
}
