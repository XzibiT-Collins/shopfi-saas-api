package com.example.perfume_budget.dto.auth.response;

import com.example.perfume_budget.enums.FrontDeskPermission;
import com.example.perfume_budget.enums.UserRole;
import lombok.Builder;

import java.util.Set;

@Builder
public record AuthResponse(
        long id,
        String email,
        String fullName,
        String profilePicture,
        UserRole role,
        Set<FrontDeskPermission> permissions
) {}
