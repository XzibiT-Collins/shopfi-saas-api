package com.example.perfume_budget.dto.front_desk.response;

import com.example.perfume_budget.enums.FrontDeskPermission;

import java.util.Set;

/** {@code version} (API-RD-11c, additive): token of the user's overrides, also the {@code ETag}; send it back as {@code If-Match}. */
public record FrontDeskUserPermissionsResponse(
        Long userId,
        Set<FrontDeskPermission> templatePermissions,
        Set<FrontDeskPermission> allowedOverrides,
        Set<FrontDeskPermission> deniedOverrides,
        Set<FrontDeskPermission> effectivePermissions,
        String version
) {
    public FrontDeskUserPermissionsResponse(Long userId, Set<FrontDeskPermission> templatePermissions,
                                            Set<FrontDeskPermission> allowedOverrides,
                                            Set<FrontDeskPermission> deniedOverrides,
                                            Set<FrontDeskPermission> effectivePermissions) {
        this(userId, templatePermissions, allowedOverrides, deniedOverrides, effectivePermissions, null);
    }
}
