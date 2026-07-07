package com.example.perfume_budget.dto.front_desk.request;

import com.example.perfume_budget.enums.FrontDeskPermission;
import jakarta.validation.constraints.NotNull;

import java.util.Set;

public record FrontDeskUserPermissionOverrideUpdateRequest(
        @NotNull Set<FrontDeskPermission> allowedPermissions,
        @NotNull Set<FrontDeskPermission> deniedPermissions
) {
}
