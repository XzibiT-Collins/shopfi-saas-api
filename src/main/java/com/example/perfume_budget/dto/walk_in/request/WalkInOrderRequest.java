package com.example.perfume_budget.dto.walk_in.request;

import com.example.perfume_budget.enums.DiscountType;
import com.example.perfume_budget.enums.WalkInPaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public record WalkInOrderRequest(
        Long registeredUserId,
        @Valid WalkInCustomerRequest walkInCustomer,
        @NotNull @NotEmpty List<@Valid WalkInOrderItemRequest> items,
        DiscountType discountType,
        BigDecimal discountValue,
        @NotNull WalkInPaymentMethod paymentMethod,
        @NotNull BigDecimal amountPaid,
        BigDecimal splitCashAmount,
        BigDecimal splitMobileAmount
) {
}
