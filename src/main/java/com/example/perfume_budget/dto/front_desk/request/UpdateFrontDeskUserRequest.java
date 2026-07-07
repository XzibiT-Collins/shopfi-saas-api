package com.example.perfume_budget.dto.front_desk.request;

import jakarta.validation.constraints.Email;

public record UpdateFrontDeskUserRequest(
        String fullName,
        @Email String email,
        String password,
        Boolean isActive
) {
}
