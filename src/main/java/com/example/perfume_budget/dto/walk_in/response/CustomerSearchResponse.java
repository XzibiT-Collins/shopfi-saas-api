package com.example.perfume_budget.dto.walk_in.response;

import lombok.Builder;

@Builder
public record CustomerSearchResponse(
        Long id,
        String fullName,
        String email,
        String phone
) {
}
