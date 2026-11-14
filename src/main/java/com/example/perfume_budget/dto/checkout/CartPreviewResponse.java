package com.example.perfume_budget.dto.checkout;

import com.example.perfume_budget.dto.tax.TaxCalculationResponse;
import lombok.Builder;

/**
 * Server-computed totals for the customer's current cart: exactly what checkout would charge right now
 * (same pricing code path), without creating an order and without consuming the coupon.
 */
@Builder
public record CartPreviewResponse(
        String subtotal,              // net of product/shop discounts, before the coupon
        String originalSubtotal,      // gross, before any discount
        String automaticDiscountAmount,
        String couponDiscountAmount,  // "GHS 0.00" when no (valid) coupon
        String discountedSubtotal,    // subtotal minus coupon discount (the taxable base)
        TaxCalculationResponse taxes,
        String deliveryFee,           // no delivery fee exists yet: always "GHS 0.00"
        String totalAmount,           // what Paystack will be asked to charge
        CouponPreview coupon          // null when no coupon was supplied
) {
    /** {@code reason} is null when valid; see docs/api/checkout.md for the codes. */
    public record CouponPreview(String code, boolean valid, String reason, String message) {}
}
