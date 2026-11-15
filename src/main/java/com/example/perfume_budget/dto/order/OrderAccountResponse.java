package com.example.perfume_budget.dto.order;

import lombok.Builder;

/** Owner of an order as shown to an ADMIN only (never serialised for a customer). */
@Builder
public record OrderAccountResponse(
        Long id,
        String fullName,
        String email
) {}
