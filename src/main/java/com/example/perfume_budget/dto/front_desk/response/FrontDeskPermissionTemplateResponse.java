package com.example.perfume_budget.dto.front_desk.response;

import com.example.perfume_budget.enums.FrontDeskPermission;

import java.util.Set;

/** {@code version} (API-RD-11c, additive): the concurrency token, also the {@code ETag}; send it back as {@code If-Match}. */
public record FrontDeskPermissionTemplateResponse(
        Set<FrontDeskPermission> permissions,
        String version
) {
    public FrontDeskPermissionTemplateResponse(Set<FrontDeskPermission> permissions) {
        this(permissions, null);
    }
}
