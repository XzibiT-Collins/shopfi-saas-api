package com.example.perfume_budget.dto.walk_in.response;

import com.example.perfume_budget.enums.DiscountType;
import com.example.perfume_budget.enums.WalkInPaymentMethod;
import lombok.Builder;

import java.util.List;

/** Server-computed totals of a draft walk-in sale. Same field meanings and money format as {@link WalkInOrderResponse}. */
@Builder
public record WalkInQuoteResponse(
        List<WalkInQuoteItemResponse> items,
        String originalSubtotal,        // gross, before automatic discount
        String automaticDiscountAmount, // product/shop discount baked into subtotal
        String subtotal,                // net of automatic discount, before the manual discount
        DiscountType discountType,
        String discountValue,           // "10.00%" for PERCENTAGE, "GHS 10.00" for FLAT, "GHS 0.00" when none
        String discountAmount,          // manual front-desk discount
        List<WalkInQuoteTaxResponse> taxes,
        String totalTaxAmount,
        String totalAmount,
        WalkInPaymentMethod paymentMethod,
        String amountPaid,              // null when the draft has no amount yet
        String changeGiven,             // null unless paymentMethod CASH and amountPaid are given
        String balanceDue               // null unless amountPaid is given: what is still owed (GHS 0.00 when covered)
) {
}
