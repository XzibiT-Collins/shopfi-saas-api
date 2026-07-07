package com.example.perfume_budget.dto.tax.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TaxResponse(
        Long id,
        String name,
        String code,
        BigDecimal rate,
        Boolean isActive,
        Boolean isCompound,
        Integer applyOrder,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
