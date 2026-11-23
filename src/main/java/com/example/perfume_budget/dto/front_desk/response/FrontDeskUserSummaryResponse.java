package com.example.perfume_budget.dto.front_desk.response;

import com.example.perfume_budget.enums.UserRole;

/** {@code permissionSummary} (API-RD-11d, additive) answers a "Custom 6 of 8" column without one call per user. */
public record FrontDeskUserSummaryResponse(
        Long id,
        String fullName,
        String email,
        boolean isActive,
        UserRole role,
        PermissionSummary permissionSummary
) {
    public FrontDeskUserSummaryResponse(Long id, String fullName, String email, boolean isActive, UserRole role) {
        this(id, fullName, email, isActive, role, null);
    }

    /**
     * @param effectiveCount      permissions the user can use now (template plus overrides)
     * @param totalCount          all front-desk permissions that exist
     * @param allowedOverrideCount overrides that grant a permission the template does not (no-op overrides are not counted)
     * @param deniedOverrideCount  overrides that remove a permission the template grants (no-op overrides are not counted)
     * @param isCustom            the user's effective set differs from the default template
     */
    public record PermissionSummary(int effectiveCount, int totalCount, int allowedOverrideCount,
                                    int deniedOverrideCount, boolean isCustom) {
    }
}
