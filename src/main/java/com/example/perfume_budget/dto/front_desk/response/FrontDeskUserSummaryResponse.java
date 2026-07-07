package com.example.perfume_budget.dto.front_desk.response;

import com.example.perfume_budget.enums.UserRole;

public record FrontDeskUserSummaryResponse(
        Long id,
        String fullName,
        String email,
        boolean isActive,
        UserRole role
) {
}
