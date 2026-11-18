package com.example.perfume_budget.enums;

public enum OrderProcessingStatus {
    PENDING,
    PACKING,
    OUT_FOR_DELIVERY,
    DELIVERED,
    CANCELLED,
    /** ORD-07: a DELIVERED, paid order whose goods came back (whole order). Final; the refund is acknowledged by hand. */
    RETURNED
}
