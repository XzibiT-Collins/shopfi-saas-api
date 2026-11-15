package com.example.perfume_budget.enums;

/** Who cancelled an order (ORD-01). ADMIN cancels are never revived by a late payment: they become refund-required. */
public enum CancelSource {
    CUSTOMER,
    ADMIN,
    EXPIRY
}
