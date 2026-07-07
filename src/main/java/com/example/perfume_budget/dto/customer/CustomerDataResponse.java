package com.example.perfume_budget.dto.customer;

import lombok.Builder;

import java.time.LocalDate;

@Builder
public record CustomerDataResponse(
   Long id,
   String fullName,
   String email,
   Long orderCount,
   String totalSpent,
   boolean isActive,
   LocalDate dateJoined
) {}
