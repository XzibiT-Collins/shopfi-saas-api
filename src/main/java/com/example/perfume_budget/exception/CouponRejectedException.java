package com.example.perfume_budget.exception;

/** A coupon cannot be applied. Still a 400 with the original message; {@code reason} feeds the cart preview. */
public class CouponRejectedException extends BadRequestException {
    public enum Reason { NOT_FOUND, INACTIVE, NOT_STARTED, EXPIRED, USAGE_LIMIT, MIN_AMOUNT, WITH_SALE_ITEMS }

    private final Reason reason;

    public CouponRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
