package com.example.perfume_budget.dto.front_desk.response;

import com.example.perfume_budget.enums.FrontDeskPermission;

import java.util.Set;

public record FrontDeskPermissionTemplateResponse(
        Set<FrontDeskPermission> permissions
) {
}
