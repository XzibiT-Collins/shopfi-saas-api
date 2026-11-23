package com.example.perfume_budget.dto.walk_in.request;

import com.example.perfume_budget.enums.DiscountType;
import com.example.perfume_budget.enums.WalkInPaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * A draft sale to price (API-RD-03 AC1). No customer fields: pricing does not depend on who buys, and a quote must not
 * carry or store customer PII. {@code paymentMethod} and {@code amountPaid} are optional; with CASH and an amount the
 * quote also returns the change.
 */
public record WalkInQuoteRequest(
        @NotNull @NotEmpty List<@Valid WalkInOrderItemRequest> items,
        DiscountType discountType,
        BigDecimal discountValue,
        WalkInPaymentMethod paymentMethod,
        BigDecimal amountPaid
) {
}
