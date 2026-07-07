package com.example.perfume_budget.dto.front_desk.response;

import com.example.perfume_budget.enums.FrontDeskPermission;

import java.util.Set;

public record FrontDeskUserPermissionsResponse(
        Long userId,
        Set<FrontDeskPermission> templatePermissions,
        Set<FrontDeskPermission> allowedOverrides,
        Set<FrontDeskPermission> deniedOverrides,
        Set<FrontDeskPermission> effectivePermissions
) {
}
