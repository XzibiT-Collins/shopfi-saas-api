package com.example.perfume_budget.dto.tax.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** {@code version} (API-RD-11c, additive) is the concurrency token: send it back as {@code If-Match} on the update. */
public record TaxResponse(
        Long id,
        String name,
        String code,
        BigDecimal rate,
        Boolean isActive,
        Boolean isCompound,
        Integer applyOrder,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String version
) {
}
