package com.example.perfume_budget.dto.sales;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A single sale event of a product: when, how many, by whom, from where, for how much. */
public record SaleLineResponse(
        LocalDateTime dateOfSale,
        int quantity,
        String soldBy,
        String channel,
        String locationName,
        Integer balanceAfterAtLocation,
        String referenceId,
        BigDecimal amount
) {
}
