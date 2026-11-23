package com.example.perfume_budget.dto.walk_in.response;

import com.example.perfume_budget.enums.DiscountType;
import com.example.perfume_budget.enums.WalkInOrderStatus;
import com.example.perfume_budget.enums.WalkInPaymentMethod;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

@Builder(toBuilder = true)
public record WalkInOrderResponse(
        String orderNumber,
        String customerName,
        String customerPhone,
        String processedBy,
        DiscountType discountType,
        String discountValue,
        WalkInPaymentMethod paymentMethod,
        WalkInOrderStatus status,
        String subtotal,              // net of automatic (product/shop) discount
        String originalSubtotal,      // gross, before automatic discount
        String automaticDiscountAmount, // product/shop discount baked into subtotal
        String discountAmount,        // manual front-desk discount
        String totalTaxAmount,
        String totalAmount,
        String amountPaid,
        String changeGiven,
        Boolean receiptPrinted,
        LocalDateTime createdAt,
        List<WalkInOrderItemResponse> items,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        Boolean idempotentReplay      // only on POST /order: true when the answer is the original sale of this Idempotency-Key
) {
}
