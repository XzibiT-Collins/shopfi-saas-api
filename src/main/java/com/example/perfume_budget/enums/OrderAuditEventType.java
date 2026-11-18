package com.example.perfume_budget.enums;

public enum OrderAuditEventType {
    STATUS_CHANGED,
    CANCELLED,
    REFUND_ACKNOWLEDGED,
    /** ORD-02: goods of a paid order cancelled while OUT_FOR_DELIVERY came back and were restocked. */
    RETURN_RECEIVED,
    /** ORD-07: a DELIVERED order was returned (restocked, sale reversed, refund now required). */
    RETURNED
}
