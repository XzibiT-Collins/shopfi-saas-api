package com.example.perfume_budget.dto.customer;

import com.example.perfume_budget.dto.delivery_detail.response.DeliveryDetailResponse;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

@Builder
public record CustomerFullDetailsResponse(
        Long id,
        String fullName,
        String email,
        LocalDateTime dateJoined,
        boolean isActive,
        List<DeliveryDetailResponse> addresses,
        String totalSpent,
        Long orderCount
) {}