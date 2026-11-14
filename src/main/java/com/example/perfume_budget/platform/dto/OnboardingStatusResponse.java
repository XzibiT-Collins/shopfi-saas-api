package com.example.perfume_budget.platform.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Public onboarding status. Deliberately carries no owner email, ids or schema name. URLs only when ACTIVE. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OnboardingStatusResponse(String slug, String status, String storefrontUrl, String adminLoginUrl) {
}
